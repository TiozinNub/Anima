package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

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
     * How far {@link #deepEnoughToSwimIn} will follow a column of water before it stops counting.
     * Any water taller than a body is deep enough, so the answer arrives within a cell or two of
     * the body every time; this only bounds the scan over an ocean.
     */
    private static final int COLUMN_SCAN = 8;

    /**
     * How far above the waterline a treading body keeps its eyes — enough that the whole head is
     * clear, because a head half under does not read as treading water, it reads as going under.
     */
    private static final double HEAD_CLEARANCE = 0.3;

    /**
     * How much of a swimming body rides clear of the surface — its back, and no more.
     *
     * <p>Vanilla's float threshold is 0.4, which against the 0.6 swimming box leaves a third of
     * the body out and reads as skimming across a lake (Luiz: "skids along over the surface"); it
     * is meant for bodies of every shape and errs high.
     */
    private static final double BACK_ABOVE_WATER = 0.1;
    /** Slack around the ride depth, so a swimmer settles rather than buzzing about the waterline. */
    private static final double RIDE_BAND = 0.08;
    /** How hard a swimmer pulls itself down to its ride depth — a sink, not a dive. */
    private static final float RIDE_THROTTLE = 0.35F;

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
    private static final float DIVE_THROTTLE = 1.0F;

    /**
     * The pressure at which the breath need takes the vertical away from the route: past this, a
     * dive becomes a surfacing whatever the route says, because a plan is drawn against the air
     * the body had when it was made. Set at the gauge's own urgent band, not a tick count, so
     * retuning what "gasping" means retunes this with it.
     */
    private static final double PANIC_PRESSURE = 0.9;

    /**
     * How far above the target a descending body stops driving down, in blocks — its braking
     * distance. Water keeps pulling after the input stops, so aiming AT the target overshoots it.
     */
    private static final double BRAKE_MARGIN = 0.55;

    /**
     * How close to the target cell's floor a body may sink before it swims back up, in blocks.
     * Small, because the floor is a cell boundary and crossing one rewrites which cell the body is
     * in — which is the unit every plan is written in.
     */
    private static final double FLOOR_MARGIN = 0.15;

    private final AgentBody body;

    private State state = State.DRY;
    private boolean swimming;
    private int graceTicks;
    /** Whether the breath need is past {@link #PANIC_PRESSURE} — see {@link #stateOf}. */
    private boolean outOfBreath;
    /**
     * Whether the route last put this body under, rather than on top of, the water.
     *
     * <p>A latch, not a per-tick reading: the route goes quiet while it re-plans, and a body that
     * treats silence as "float" undoes its own descent — the intent blanks, buoyancy takes the
     * vertical back, and the next plan starts from the cell the body was pushed up into. Once a
     * second, that hovers a settler at the surface of a twenty-block pool forever.
     *
     * <p>Set and cleared only on things the route actually says. Silence changes nothing.
     */
    private boolean routeHasUsUnder;

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
        switch (intent) {
            case DIVE, CROSS_UNDER -> this.routeHasUsUnder = true;
            case SURFACE, CROSS, EXIT -> this.routeHasUsUnder = false;
            default -> { } // none: the route is between plans and has said nothing. Hold.
        }
        if (!inWater || this.outOfBreath) {
            this.routeHasUsUnder = false;
        }
        this.state = stateOf(inWater, deep, intent);

        // Sprint is not a mood in water, it is the gear: vanilla swims at a flat 0.02 either
        // way and changes the DRAG instead (0.9 sprinting against 0.8), so terminal speed is
        // 0.02/(1-drag) — 0.2 a tick against 0.1, exactly twice. It is why a Person that never
        // sprinted crossed water at half a player's pace; vanilla will not even give out the
        // swimming pose unless you are sprinting.
        //
        // Through driveSprint, so the metabolism still has its say, as it does in vanilla. No
        // double charge: the body bills swimming and sprinting by the metre and picks one.
        if (inWater) {
            this.body.driveSprint(this.swimming);
        }

        driveVertical(entity, inWater, deep, intent);
        aimTheHead(entity, intent);
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
        if (routeHasUsUnder) {
            holdDepth(entity, this.body.navigator().waterTargetY());
            return;
        }
        if (deep) {
            double submerged = entity.getFluidHeight(FluidTags.WATER);
            if (entity.isVisuallySwimming()) {
                // Held from both sides, unlike treading: a swimmer sank only by being left
                // to gravity, and a fast crossing is over before gravity does anything, so once
                // sprinting and long strokes made it quick it planed across the top of the lake
                // (Luiz: "skids along over the surface"). Pulling it down is diving to a
                // shallower mark.
                double ride = entity.getBbHeight() - BACK_ABOVE_WATER;
                if (submerged > ride + RIDE_BAND) {
                    this.body.driveJump();
                } else if (submerged < ride - RIDE_BAND) {
                    this.body.driveDown(RIDE_THROTTLE);
                }
                return;
            }
            if (submerged > entity.getEyeHeight() - HEAD_CLEARANCE) {
                this.body.driveJump(); // upright, treading: keep the head out
                return;
            }
        }
        if (inWater && intent == Navigator.WaterIntent.EXIT) {
            this.body.driveJump();
        }
    }

    /**
     * Points the head where the body is actually going, which out of water is always level and in
     * water is not.
     *
     * <p>The follower pins pitch flat on every steering tick, which is right on land. A swimmer's
     * gaze is not: rendered level while it sinks it reads as a body being dragged (Luiz: "they
     * should look up and down while diving"), and vanilla tilts the whole model by the pitch when
     * it draws a swimmer, so this is not decoration.
     *
     * <p>Written after the vertical drive and after the follower's own {@code face}, which would
     * otherwise flatten it again; eased rather than snapped, so the head turns instead of
     * flicking.
     */
    private void aimTheHead(LivingEntity entity, Navigator.WaterIntent intent) {
        if (this.state == State.DRY || this.state == State.WADING) {
            return; // on its feet: the follower's level gaze is the right one
        }
        // From the body's own motion, not the waypoint: the waypoint is only ever a stroke
        // away, so a body going straight down toward a target two blocks below tilted five
        // degrees and read as level. Where it is MOVING is ninety degrees whether the next stroke
        // is two blocks or twenty.
        Vec3 motion = entity.getDeltaMovement();
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        double wanted = 0.0;
        if (motion.lengthSqr() > MOVING_ENOUGH_TO_AIM) {
            // Degrees, positive downward — vanilla's convention, and the sign the renderer tilts by.
            wanted = Mth.clamp(Math.toDegrees(Math.atan2(-motion.y, horizontal)), -MAX_LOOK, MAX_LOOK);
        }
        entity.setXRot((float) Mth.lerp(LOOK_EASE, entity.getXRot(), wanted));
    }

    /** Steepest the head will angle, short of the ninety degrees that would look like a somersault. */
    private static final double MAX_LOOK = 85.0;
    /** Below this speed (squared) there is no direction worth pointing at, so the head stays level. */
    private static final double MOVING_ENOUGH_TO_AIM = 0.0004;
    /** How much of the way to the wanted angle a single tick moves — a turn of the head, not a flick. */
    private static final double LOOK_EASE = 0.25;

    /**
     * Swims toward the depth the route asked for, and holds the body INSIDE that cell once there.
     *
     * <p><b>Asymmetric.</b> Water keeps pulling after the input stops, so a band aimed AT the
     * target sails through it — and once the feet cross into the next cell down, the next re-path
     * plans from there and dives again toward the new floor, ratcheting down the whole column a
     * cell at a time (Luiz: "a person still sinks all the way to the bottom"). Nothing about it
     * looks like a bug from inside a single tick.
     *
     * <p>So the body stops driving down with {@link #BRAKE_MARGIN} still to fall, coasts on its
     * momentum, and presses up within {@link #FLOOR_MARGIN} of the cell floor — the line it must
     * not cross, because that is where "which cell am I in" changes and every plan is in cells.
     */
    private void holdDepth(LivingEntity entity, double targetY) {
        double off = entity.getY() - targetY;
        if (off > BRAKE_MARGIN) {
            this.body.driveDown(DIVE_THROTTLE);
        } else if (off < FLOOR_MARGIN) {
            this.body.driveJump();
        }
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
                // none while still holding a depth is the re-path gap, not a change of plan.
                default -> this.routeHasUsUnder ? State.DIVING : State.CROSSING;
            };
        }
        return deep ? State.TREADING : State.WADING;
    }

    /**
     * Whether the water HERE is deep enough to swim in — the height of the water itself, bed to
     * surface, not what happens to be under the feet.
     *
     * <p>Every question the body can ask about ITSELF is circular, and each was tried and taken
     * out: {@code onGround} is false for the tick or two of dropping off a bank into a stream;
     * "are my eyes under water" is answered by the pose that is being decided (the swimming box
     * puts the eye at 0.4, low enough to stay wet in a one-deep stream, so the reflex keeps
     * pressing and the pose holds itself up); fluid height cannot exceed the body's own box, so it
     * shrinks with the pose too.
     *
     * <p>Nor does where the body sits in the column matter: the first version counted water
     * DOWNWARD from the feet, so a body swimming a flooded tunnel one cell off the bed found one
     * cell of water, decided it was a puddle and walked the rest along the bottom (Luiz: "in the
     * water tunnel case, they just walk on the floor, inside water"). Compared against the
     * STANDING height deliberately: the swimming box is shorter, and measuring against it would be
     * the same circle by another route.
     */
    private static boolean deepEnoughToSwimIn(LivingEntity entity) {
        BlockPos.MutableBlockPos cell = entity.blockPosition().mutable();
        if (!isWater(entity, cell)) {
            cell.move(Direction.DOWN); // bobbing in the air over the surface
        }
        if (!isWater(entity, cell)) {
            return false;
        }
        int column = 1;
        for (int i = 0; i < COLUMN_SCAN && isWater(entity, cell.move(Direction.DOWN)); i++) {
            column++;
        }
        cell.set(entity.blockPosition());
        for (int i = 0; i < COLUMN_SCAN && isWater(entity, cell.move(Direction.UP)); i++) {
            column++;
        }
        return column >= Mth.ceil(entity.getDimensions(Pose.STANDING).height());
    }

    private static boolean isWater(LivingEntity entity, BlockPos cell) {
        return entity.level().getFluidState(cell).is(FluidTags.WATER);
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

    /**
     * One line for a readout: what it is doing, plus a note when the latch and the state
     * disagree. They disagree while the grace runs out — the body has left the water or the
     * follower has stopped steering, but it still counts as swimming for a few ticks.
     */
    public String describe() {
        boolean swimmingState = this.state == State.CROSSING || this.state == State.CLIMBING_OUT
                || this.state == State.DIVING || this.state == State.SURFACING;
        return this.state + (this.swimming && !swimmingState ? " (still swimming)" : "");
    }
}
