package dev.luizloyola.anima.mod.nav;

import net.minecraft.server.level.ServerLevel;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.nav.LaidBlocks;
import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.RiseState;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.Waypoint;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.brain.AgentBlockBreaker;
import dev.luizloyola.anima.mod.brain.AgentBlockPlacer;
import dev.luizloyola.anima.mod.brain.AgentRiser;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The follower's hand on the blocks a route moves (docs/superpowers/specs/2026-09-28-bridging-design.md):
 * lays a deck and steps onto it, rises on a pillar block, cuts a soft step and puts it back
 * underfoot.
 *
 * <p><b>Read off the live world every tick, never off a remembered phase.</b> A deck already there
 * is stepped onto, a lip already cut is climbed into, a notch already entered is filled under the
 * feet — so a restart mid-act, or somebody else's hand, only moves where the act picks up.
 *
 * <p>The deck is laid from where the body stands, not from the lip: a Person is no player, and
 * sneaking keeps no mob from walking off an edge. The placer reaches the cell from the middle of
 * the one before it.
 */
final class GroundWork {

    enum Result { WORKING, DONE, REFUSED }

    /** How long one act may take before the follower gives it up — ten seconds of a lay that will not go. */
    private static final int ACT_TIMEOUT = 200;
    /** How near the middle of a waypoint the feet must be to have arrived on it. */
    private static final double ARRIVED = 0.3;
    /** The pace onto a deck just laid: the careful throttle, since it is the edge of a drop. */
    private static final float ONTO_DECK = 0.45F;
    /** How near the notch a body presses its jump into it. */
    private static final double JUMP_RANGE = 1.2;
    /**
     * How long a body with nothing to put back waits in the notch for the drop of what it cut.
     * Vanilla holds a broken block's drop back from pickup for ten ticks, and a body that jumps in
     * at once is quicker than that: it gave up, empty-handed, with the lip it cut lying at its feet
     * (gauntlet K7 and K9, 2026-09-28), and left the step changed.
     */
    private static final int DROP_WAIT = 40;
    /**
     * How many times a cut that failed is started again. The arm gives up when the block changes
     * under it, and dirt a cut leaves open to the sky can turn to grass beside a grassed step on a
     * random tick (gauntlet K8, 2026-09-28): still soft, still the step.
     */
    private static final int CUT_RETRIES = 2;

    private final AgentBody person;
    private final AgentBlockPlacer placer;
    private int workingIndex = -1;
    private int ticks;
    /** Ticks this act has stood in its notch with nothing to put back. */
    private int waitedForDrop;
    /** Cuts of this act that failed and were started again. */
    private int cutRetries;
    /** The run the decks and pillar blocks of this route are recorded under, and the waypoint that last added to it. */
    private int deckRun;
    private int deckIndex = -2;
    private int pillarRun;
    private int pillarIndex = -2;
    /** The run a pillar block taken from beside came from — the block laid next goes into it. */
    private int takenRun;
    /** Whether the riser or the arm is busy on this hand's account — only those are called off. */
    private boolean ownsRiser;
    private boolean ownsBreaker;
    /**
     * What each cell a scale cut held, so the same block goes back. Not saved: after a restart the
     * step is put back from whatever soft ground the body carries.
     */
    private final Map<BlockPos, String> cut = new HashMap<>();
    private String why = "";

    GroundWork(AgentBody person) {
        this.person = person;
        this.placer = new AgentBlockPlacer(person);
    }

    /** Calls off whatever this hand started — the route changed, or the walk ended. */
    void reset() {
        if (this.ownsRiser && this.person.riser().state() == RiseState.RISING) {
            this.person.riser().abort();
        }
        if (this.ownsBreaker && this.person.blockBreaker().state() == BreakState.BREAKING) {
            this.person.blockBreaker().abort();
        }
        this.ownsRiser = false;
        this.ownsBreaker = false;
        this.workingIndex = -1;
        this.ticks = 0;
        this.cut.clear();
        this.deckIndex = -2;
        this.pillarIndex = -2;
        this.takenRun = 0;
    }

    /** Why the last act was refused, for the journal. */
    String why() {
        return this.why;
    }

    /**
     * One tick of the act that enters waypoint {@code index}, {@code to}, from the cell {@code from}.
     * {@link Result#DONE} once the body stands on it.
     */
    Result tick(int index, BlockPos from, Waypoint to) {
        if (index != this.workingIndex) {
            this.workingIndex = index;
            this.ticks = 0;
            this.waitedForDrop = 0;
            this.cutRetries = 0;
        }
        if (++this.ticks > ACT_TIMEOUT) {
            return refuse("gave up on the " + name(to) + " at " + at(to));
        }
        if (arrived(to)) {
            this.person.driveSneak(false);
            return Result.DONE;
        }
        return switch (to.move()) {
            case BRIDGE -> bridge(index, to);
            case PILLAR -> pillar(index, to);
            case SCALE -> scale(from, to);
            case LOWER -> lower(to);
            default -> Result.DONE;
        };
    }

    private Result bridge(int index, Waypoint to) {
        Level level = this.person.level();
        BlockPos deck = new BlockPos(to.x(), to.y() - 1, to.z());
        this.person.driveSneak(true);
        if (level.getBlockState(deck).canBeReplaced()) {
            this.person.stopMoving();
            this.person.faceBlock(deck);
            String item = Laying.pick(this.person.inventory());
            if (item == null) {
                return refuse("nothing to lay at " + deck.toShortString());
            }
            if (!this.placer.place(item, new Pos(deck.getX(), deck.getY(), deck.getZ()))) {
                return refuse("the deck at " + deck.toShortString() + " would not go");
            }
            log("laid", "a deck of " + item + " at " + deck.toShortString());
            if (index != this.deckIndex + 1 || this.deckRun == 0) {
                this.deckRun = ledger().open(LaidBlocks.Kind.DECK);
            }
            this.deckIndex = index;
            record(deck, item, LaidBlocks.Kind.DECK, this.deckRun);
            return Result.WORKING;
        }
        steer(to, ONTO_DECK);
        return Result.WORKING;
    }

    private Result pillar(int index, Waypoint to) {
        AgentRiser riser = this.person.riser();
        if (riser.state() == RiseState.RISING) {
            return Result.WORKING; // the riser drives the legs; it ticks after the Navigator
        }
        AgentBlockBreaker breaker = this.person.blockBreaker();
        if (breaker.state() == BreakState.BREAKING) {
            this.person.stopMoving();
            return Result.WORKING; // prying a block off the pillar beside
        }
        if (breaker.state() == BreakState.FAILED && this.ownsBreaker) {
            this.ownsBreaker = false;
            breaker.abort();
            return refuse("the pillar beside " + at(to) + " would not give a block");
        }
        if (riser.state() == RiseState.FAILED && this.ownsRiser) {
            this.ownsRiser = false;
            riser.abort();
            return refuse("the pillar at " + at(to) + " would not rise");
        }
        BlockPos feet = this.person.blockPosition();
        if (feet.getX() != to.x() || feet.getZ() != to.z()) {
            steer(to, ONTO_DECK);
            return Result.WORKING;
        }
        if (feet.getY() >= to.y() || !this.person.onGround()) {
            this.person.stopMoving();
            return Result.WORKING; // up already, settling
        }
        // Beside a recorded pillar the block comes off the pillar, a level above the feet, so the
        // search's balance holds: nothing taken from the pocket. Prised into the hand, not dropped.
        BlockPos beside = recordedBeside(feet);
        if (beside != null && this.takenRun == 0) {
            LaidBlocks.Row row = ledger().at(pos(beside)).orElse(null);
            if (!breaker.pry(pos(beside))) {
                return refuse("could not reach the pillar beside " + at(to));
            }
            this.ownsBreaker = true;
            this.takenRun = row != null ? row.run() : ledger().open(LaidBlocks.Kind.PILLAR);
            ledger().remove(pos(beside));
            log("taking", "a pillar block from " + beside.toShortString());
            return Result.WORKING;
        }
        String item = Laying.pick(this.person.inventory());
        if (item == null) {
            return refuse("nothing to rise on at " + at(to));
        }
        if (!riser.up(item)) {
            return refuse("the pillar at " + at(to) + " would not start");
        }
        this.ownsRiser = true;
        int run;
        if (this.takenRun != 0) {
            run = this.takenRun;
            this.takenRun = 0;
        } else {
            if (index != this.pillarIndex + 1 || this.pillarRun == 0) {
                this.pillarRun = ledger().open(LaidBlocks.Kind.PILLAR);
            }
            run = this.pillarRun;
        }
        this.pillarIndex = index;
        record(feet, item, LaidBlocks.Kind.PILLAR, run);
        log("rising", "on " + item + " at " + feet.toShortString());
        return Result.WORKING;
    }

    /**
     * Down one: the recorded pillar block underfoot prised loose, and a drop onto the next one — or
     * the ground the pillar stands on. The record says what is below; the live world has to agree
     * before the swing, or the body does not go.
     */
    private Result lower(Waypoint to) {
        BlockPos block = new BlockPos(to.x(), to.y(), to.z());
        BlockPos feet = this.person.blockPosition();
        AgentBlockBreaker breaker = this.person.blockBreaker();
        if (breaker.state() == BreakState.BREAKING) {
            this.person.stopMoving();
            return Result.WORKING;
        }
        if (breaker.state() == BreakState.FAILED && this.ownsBreaker) {
            this.ownsBreaker = false;
            breaker.abort();
            return refuse("the pillar at " + at(to) + " would not give");
        }
        Level level = this.person.level();
        if (level.getBlockState(block).canBeReplaced()) {
            this.person.stopMoving();
            return Result.WORKING; // broken already: falling, or about to
        }
        if (feet.getX() != to.x() || feet.getZ() != to.z()) {
            steer(to, ONTO_DECK);
            return Result.WORKING;
        }
        if (level.getBlockState(block.below()).canBeReplaced()) {
            return refuse("nothing under the pillar at " + at(to) + " to land on");
        }
        this.person.stopMoving();
        if (!breaker.pry(pos(block))) {
            return refuse("could not reach the pillar underfoot at " + at(to));
        }
        this.ownsBreaker = true;
        ledger().remove(pos(block));
        log("lowering", "through " + block.toShortString());
        return Result.WORKING;
    }

    /**
     * A recorded pillar block beside the feet, one level up — the block a climber takes. Nothing need
     * be under it: the one under it is the block the last step took. Null when there is none.
     */
    private @org.jspecify.annotations.Nullable BlockPos recordedBeside(BlockPos feet) {
        Level level = this.person.level();
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos cell = feet.above().relative(side);
            LaidBlocks.Row row = ledger().at(pos(cell)).orElse(null);
            if (row != null && row.kind() == LaidBlocks.Kind.PILLAR
                    && !level.getBlockState(cell).canBeReplaced()) {
                return cell;
            }
        }
        return null;
    }

    private void record(BlockPos cell, String item, LaidBlocks.Kind kind, int run) {
        AgentId who = this.person.agentId();
        PartyId party = null;
        if (who != null && this.person.level() instanceof ServerLevel server) {
            party = PartyData.get(server.getServer()).currentPartyOf(who).orElse(null);
        }
        ledger().lay(pos(cell), item, kind, who, party, this.person.level().getGameTime(), run);
    }

    private LaidBlocks ledger() {
        return LaidBlocksData.get(((ServerLevel) this.person.level()).getServer()).laid();
    }

    private static Pos pos(BlockPos cell) {
        return new Pos(cell.getX(), cell.getY(), cell.getZ());
    }

    /**
     * A scale from {@code from}: its lip cut from the top down, a jump into the notch, and the cut
     * blocks laid back one at a time under the feet.
     */
    private Result scale(BlockPos from, Waypoint to) {
        AgentRiser riser = this.person.riser();
        if (riser.state() == RiseState.RISING) {
            return Result.WORKING;
        }
        if (riser.state() == RiseState.FAILED && this.ownsRiser) {
            this.ownsRiser = false;
            riser.abort();
            return refuse("the step at " + at(to) + " would not go back");
        }
        BlockPos feet = this.person.blockPosition();
        int floor = from.getY();
        if (feet.getX() == to.x() && feet.getZ() == to.z() && feet.getY() > floor) {
            if (feet.getY() >= to.y() || !this.person.onGround()) {
                this.person.stopMoving();
                return Result.WORKING;
            }
            String item = Laying.putBack(this.person.inventory(), this.cut.get(feet));
            if (item == null) {
                if (++this.waitedForDrop < DROP_WAIT) {
                    this.person.stopMoving();
                    return Result.WORKING; // the lip's own drop, not yet in hand
                }
                return refuse("nothing to put back at " + feet.toShortString());
            }
            if (!riser.up(item)) {
                return refuse("the step at " + feet.toShortString() + " would not go back");
            }
            this.ownsRiser = true;
            return Result.WORKING;
        }
        AgentBlockBreaker breaker = this.person.blockBreaker();
        if (breaker.state() == BreakState.BREAKING) {
            this.person.stopMoving();
            return Result.WORKING;
        }
        if (breaker.state() == BreakState.FAILED && this.ownsBreaker) {
            this.ownsBreaker = false;
            breaker.abort();
            if (++this.cutRetries > CUT_RETRIES) {
                putBackFromHere(from, to);
                return refuse("the step at " + at(to) + " would not break");
            }
            return Result.WORKING; // the loop below starts the cut again
        }
        Level level = this.person.level();
        for (int y = to.y() - 1; y > floor; y--) {
            BlockPos cell = new BlockPos(to.x(), y, to.z());
            BlockState state = level.getBlockState(cell);
            if (!state.canBeReplaced()) {
                this.person.stopMoving();
                this.cut.putIfAbsent(cell, BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString());
                if (!breaker.begin(new Pos(cell.getX(), cell.getY(), cell.getZ()))) {
                    putBackFromHere(from, to);
                    return refuse("could not reach the step at " + cell.toShortString());
                }
                this.ownsBreaker = true;
                log("scaling", "cut " + cell.toShortString());
                return Result.WORKING;
            }
        }
        // All cut: up one into the notch.
        steer(to, 1.0F);
        Vec3 pos = this.person.position();
        double dx = to.x() + 0.5 - pos.x;
        double dz = to.z() + 0.5 - pos.z;
        if (this.person.onGround() && feet.getY() <= floor && dx * dx + dz * dz < JUMP_RANGE * JUMP_RANGE) {
            this.person.driveJump();
        }
        return Result.WORKING;
    }

    /**
     * A scale given up before the body got into the notch puts back what it had cut, from where it
     * stands, before the route is asked again: otherwise the next search reads the half-cut step as
     * the ground and climbs it as it now is, and the step stays changed. Best effort — only what the
     * pocket already holds goes back.
     */
    private void putBackFromHere(BlockPos from, Waypoint to) {
        Level level = this.person.level();
        for (int y = from.getY() + 1; y < to.y(); y++) {
            BlockPos cell = new BlockPos(to.x(), y, to.z());
            if (!this.cut.containsKey(cell) || !level.getBlockState(cell).canBeReplaced()) {
                continue;
            }
            String item = Laying.putBack(this.person.inventory(), this.cut.get(cell));
            if (item != null && this.placer.place(item, new Pos(cell.getX(), cell.getY(), cell.getZ()))) {
                log("put back", item + " at " + cell.toShortString());
            }
        }
    }

    private boolean arrived(Waypoint to) {
        BlockPos feet = this.person.blockPosition();
        if (feet.getX() != to.x() || feet.getZ() != to.z() || feet.getY() != to.y()
                || !this.person.onGround()) {
            return false;
        }
        Vec3 pos = this.person.position();
        double dx = to.x() + 0.5 - pos.x;
        double dz = to.z() + 0.5 - pos.z;
        return dx * dx + dz * dz <= ARRIVED * ARRIVED || to.move() != MoveType.BRIDGE;
    }

    private void steer(Waypoint to, float throttle) {
        Vec3 pos = this.person.position();
        float heading = (float) (Mth.atan2(to.z() + 0.5 - pos.z, to.x() + 0.5 - pos.x)
                * Mth.RAD_TO_DEG) - 90.0F;
        this.person.driveForward(heading, throttle);
    }

    private Result refuse(String why) {
        this.why = why;
        log("refused", why);
        return Result.REFUSED;
    }

    private void log(String event, String detail) {
        this.person.journal().record(Category.PATHFIND, event, detail);
    }

    private static String name(Waypoint to) {
        return to.move().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String at(Waypoint to) {
        return "(" + to.x() + ", " + to.y() + ", " + to.z() + ")";
    }
}
