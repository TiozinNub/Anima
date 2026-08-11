package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;

/**
 * What a body does about being in water — the one owner of vertical intent while wet, and the one
 * place that decides whether being wet is swimming, wading, treading or getting out.
 *
 * <p>It was four rules in four files and every bug they produced was invisible from inside any of
 * them: buoyancy with no set point bounced a swimmer out of the water twenty times a second; a
 * pose that answered its own question gave a knee-deep crossing seventy-one ticks of breaststroke
 * out of seventy-two; the search had no node for a submerged body; and the climb-out was riding on
 * buoyancy being over-eager, so narrowing that reflex broke two gauntlet stations at once. The
 * follower says where the body is going, the body says what it looks like, and everything between
 * is here.
 *
 * <p>Nothing below knows what shape the body is: a pet gets all of it by supplying an
 * {@link AgentBody}, and a consumer supplies only what swimming should LOOK like.
 *
 * <p>Ticked by the body once per tick, <em>after</em> the {@link Navigator}: the follower's
 * verdict for this tick is an input here, and the vertical this writes must be the last word.
 * Server side, single-threaded like every other organ.
 */
public final class Swimmer {

    /** What a wet body is doing. Derived every tick — this is a description, not a second latch. */
    public enum State {
        DRY,
        /**
         * Wet, but standing on the bottom with the head clear. Shallow water is not swimming and
         * must not look like it, whatever the route says.
         */
        WADING,
        /**
         * Deep water with nowhere to be: hold the head out and bob. What an idle body does after it
         * falls in, and the state a stopped swimmer settles into rather than sinking.
         */
        TREADING,
        /** Deep water, steering a swim leg — riding the waterline. */
        CROSSING,
        /**
         * Going down, against buoyancy — the only state in which the body's own reflex
         * to keep its head up is overruled.
         */
        DIVING,
        /** Coming up, whether because the route says so or because the air is running out. */
        SURFACING,
        /** Steering onto a bank: the one water move that has to gain height. */
        CLIMBING_OUT
    }

    /**
     * Blocks of water a body must have under it before being in it counts as swimming. Two, by
     * arithmetic: on the bed of a two-deep pool a person-shaped head is under (1.8 &lt; 2.0); in
     * one-deep there is a clear block above and a body walks.
     */
    private static final int SWIMMABLE_DEPTH = 2;

    /**
     * How far above the waterline a treading body keeps its eyes — enough that the whole head is
     * clear, because a head half under does not read as treading water, it reads as going under.
     */
    private static final double HEAD_CLEARANCE = 0.3;

    /**
     * How long a swimmer stays one after the last tick that plainly was, in ticks.
     *
     * <p>Not belt-and-braces: the swimming box is 0.6 tall, so a small rise takes all of it out of
     * the water for a tick, and the follower's answer flickers too because a tick out of the water
     * on a climb-out leg is not steered as a swim. Measured live, the pose flipped twenty times a
     * second, resizing the hitbox and broadcasting synched data on each one.
     */
    private static final int GRACE_TICKS = 10;

    /**
     * How hard a diving body swims down. Under full effort deliberately: a descent that fought
     * buoyancy at full strength would overshoot the cell the route asked for, and a body that ends
     * a dive one cell below the tunnel it meant to enter is a body wedged under a roof.
     */
    private static final float DIVE_THROTTLE = 0.6F;

    /**
     * The pressure at which the breath need takes the vertical away from the route: past this, a
     * dive becomes a surfacing whatever the route says, because a plan is drawn against the air
     * the body had when it was made. Set at the gauge's own urgent band, not a tick count, so
     * retuning what "gasping" means retunes this with it.
     */
    private static final double PANIC_PRESSURE = 0.9;

    /** How far off the route's depth a body may drift before it swims back to it, in blocks. */
    private static final double DEPTH_BAND = 0.25;

    private final AgentBody body;

    private State state = State.DRY;
    private boolean swimming;
    private int graceTicks;
    /** Whether the breath need is past {@link #PANIC_PRESSURE} — see {@link #stateOf}. */
    private boolean outOfBreath;

    public Swimmer(AgentBody body) {
        this.body = body;
    }

    /**
     * One tick of being in water: work out what this body is doing, and press up as much as that
     * calls for.
     */
    public void tick() {
        LivingEntity entity = this.body.entity();
        Navigator.WaterIntent intent = this.body.navigator().waterIntent();
        boolean inWater = entity.isInWater();
        boolean deep = inWater && deepEnoughToSwimIn(entity);
        this.outOfBreath = inWater && this.body.needs().gauge(NeedKind.BREATH)
                .map(breath -> breath.pressure() >= PANIC_PRESSURE).orElse(false);

        // The latch, and the only piece of memory here. Harder to enter than to stay in, because a
        // single live condition flickers where two do not.
        boolean plainlySwimming = deep && intent != Navigator.WaterIntent.NONE;
        if (plainlySwimming) {
            this.graceTicks = GRACE_TICKS;
        } else if (this.graceTicks > 0) {
            this.graceTicks--;
        }
        if (entity.onGround()) {
            // Feet planted: wading, or out the far side. No grace at all — leaving has a
            // crisp fact, so a body wading out is walking again before anyone sees a stroke.
            this.swimming = false;
        } else {
            this.swimming = this.swimming ? this.graceTicks > 0 : plainlySwimming;
        }
        this.state = stateOf(inWater, deep, intent);

        driveVertical(entity, inWater, deep, intent);
    }

    /**
     * The whole of the vertical input while wet.
     *
     * <p>Buoyancy is a controller with a set point, not a held key: swim-up nudges a fixed amount
     * every tick it is pressed, so pressing it whenever the body is wet swung 0.755 of a block and
     * reversed 432 times in 608 ticks on gauntlet E2 — against a set point, 0.301 and never out of
     * the water.
     *
     * <p>One set point cannot serve both shapes. Swimming, the box is 0.6 and the game's own 0.4 is
     * right; upright, 0.4 floats a 1.8 body like a cork, while pressing whenever the eyes are wet
     * sinks it until only the top of the head shows. Upright the set point is eye height less
     * {@link #HEAD_CLEARANCE}.
     *
     * <p>Nothing presses in shallow water: the gate keeps the reflex from lifting a wader off the
     * bed, which then feeds itself because the swimming box puts the eye low enough to stay wet.
     *
     * <p>The climb-out is a separate press. It used to ride on the always-on reflex, and narrowing
     * that correctly left both plunge stations unable to leave their own pools. Pressed for the
     * whole approach because there is no edge to time it against — the water lets go gradually.
     */
    private void driveVertical(LivingEntity entity, boolean inWater, boolean deep,
            Navigator.WaterIntent intent) {
        // While the route is taking this body under the surface it owns the vertical and
        // buoyancy gets no vote. Without the first a dive is a body arguing with itself; without
        // the second the body rises the moment the route stops saying DIVE — which is how a
        // crossing under a roof wedged a settler against its underside.
        boolean routeHasUsUnder = this.state == State.DIVING
                || (this.state == State.CROSSING && fullySubmerged(entity));
        if (routeHasUsUnder) {
            holdDepth(entity, this.body.navigator().waterTargetY());
            return;
        }
        if (deep) {
            double setPoint = entity.isVisuallySwimming()
                    ? entity.getFluidJumpThreshold()          // 0.6 box, riding the surface
                    : entity.getEyeHeight() - HEAD_CLEARANCE; // upright, treading, head out
            if (entity.getFluidHeight(FluidTags.WATER) > setPoint) {
                this.body.driveJump();
                return;
            }
        }
        if (inWater && intent == Navigator.WaterIntent.EXIT) {
            this.body.driveJump();
        }
    }

    /**
     * Swims toward the depth the route asked for, and stops when it is there.
     *
     * <p>A hold, not a one-way press: drag sinks a body doing nothing, buoyancy lifts one that has
     * just stopped diving. Inside the band it coasts.
     */
    private void holdDepth(LivingEntity entity, double targetY) {
        double off = entity.getY() - targetY;
        if (off > DEPTH_BAND) {
            this.body.driveDown(DIVE_THROTTLE);
        } else if (off < -DEPTH_BAND) {
            this.body.driveJump();
        }
    }

    /** Whether the body is under water all the way to the top of its box — not merely wet. */
    private static boolean fullySubmerged(LivingEntity entity) {
        return entity.getFluidHeight(FluidTags.WATER) >= entity.getBbHeight() - 0.1;
    }

    /** A description of {@link #swimming} and the water, not a second opinion about either. */
    private State stateOf(boolean inWater, boolean deep, Navigator.WaterIntent intent) {
        if (!inWater) {
            return State.DRY;
        }
        if (this.swimming) {
            return switch (intent) {
                case EXIT -> State.CLIMBING_OUT;
                case DIVE -> this.outOfBreath ? State.SURFACING : State.DIVING;
                case SURFACE -> State.SURFACING;
                default -> State.CROSSING;
            };
        }
        return deep ? State.TREADING : State.WADING;
    }

    /**
     * Whether the water under this body is deep enough to swim in — asked of the WORLD by counting
     * the column.
     *
     * <p>Every question a body can ask about ITSELF is circular and was tried and taken back out:
     * {@code onGround} is false for the tick or two of dropping off a bank, and "are my eyes under
     * water" is answered by the pose being decided — the swimming box puts the eye at 0.4, wet
     * enough in a one-deep stream that the reflex keeps pressing and the pose holds itself up.
     * Fluid height cannot exceed the body's own box either, so it shrinks with the pose.
     *
     * <p>The scan starts a cell low in case the body is bobbing over the surface, and stops as soon
     * as it has its answer.
     */
    private static boolean deepEnoughToSwimIn(LivingEntity entity) {
        BlockPos.MutableBlockPos cell = entity.blockPosition().mutable();
        if (!entity.level().getFluidState(cell).is(FluidTags.WATER)) {
            cell.move(Direction.DOWN);
        }
        for (int depth = 0; depth < SWIMMABLE_DEPTH; depth++) {
            if (!entity.level().getFluidState(cell).is(FluidTags.WATER)) {
                return false;
            }
            cell.move(Direction.DOWN);
        }
        return true;
    }

    /** What this body is doing about the water, as of the last {@link #tick()}. */
    public State state() {
        return this.state;
    }

    /**
     * Whether this body is swimming — the latched fact a pose should follow, which is not quite
     * {@code state() == CROSSING}: the grace above keeps it true across the tick or two a swimmer
     * spends clear of the water, and a body that has just been told to stop still has to finish
     * getting out of the lake.
     */
    public boolean isSwimming() {
        return this.swimming;
    }

    /** One line for a readout: what it is doing, and whether that counts as swimming. */
    public String describe() {
        return this.state + (this.swimming && this.state != State.CROSSING
                && this.state != State.CLIMBING_OUT ? " (still swimming)" : "");
    }
}
