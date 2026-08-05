package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.act.RiseState;
import dev.luizloyola.anima.core.brain.act.Riser;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Riser} port over a live {@link AgentBody} — one nerd-pole step: jump, and while
 * airborne past block height, place a carried block into the cell just vacated, then land on it.
 * Ticked from the body's server AI step, which owns the jump/place timing; the brain only asks
 * for steps.
 *
 * <p><b>The body steps to the middle of its cell before jumping.</b> The headroom check reads one
 * column ({@code feet.above(2)}) but the box is 0.6 wide, so off-centre it pokes into a
 * neighbouring column and bonks a block that check never looked at (decision: Luiz). The case
 * that bought it: a leaf one cell over, the box 0.182 into that column, 95 dead jumps in a row
 * and not one block placed.
 */
public final class AgentRiser implements Riser {
    /** Ticks a step may take before it is declared dead — a clean jump lands in ~12. */
    private static final int STEP_TIMEOUT_TICKS = 15;

    /** Ticks the centring shuffle may take before the step dies — a worst-case corner needs ~5. */
    private static final int CENTRE_TIMEOUT_TICKS = 12;

    /** Centred, per axis: the 0.6-wide box fits wholly inside up to 0.2, leaving 0.05 of margin. */
    private static final double CENTRE_TOLERANCE = 0.15;

    /** Half-throttle scuff: at ~0.1 of a block per tick the body cannot cross from outside the
     *  tolerance on one side to outside it on the other, so it settles in the middle. */
    private static final float CENTRE_THROTTLE = 0.5F;

    /**
     * Consecutive dead steps from the same cell before {@link #up} refuses, routing the caller
     * into its give-up branch. Recentring fixes the common cause; a cobweb overhead, a shoving
     * neighbour or honey underfoot defeat it, and unbounded the caller re-asks forever.
     */
    private static final int ATTEMPTS_PER_CELL = 3;

    private final AgentBody person;

    private RiseState state = RiseState.IDLE;
    private @Nullable BlockPos base;
    private @Nullable String itemId;
    private int ticks;
    /** Ticks spent shuffling toward the middle of {@link #base}; separate from {@link #ticks} so
     * the shuffle cannot eat the jump's budget. */
    private int centringTicks;
    /** True while this step is still walking to the middle of {@link #base} — see the class doc. */
    private boolean centring;
    /**
     * Body state on purpose: a cell that keeps beating them is about this body's situation, not
     * the task that asked, so it survives task churn and a mid-climb suspension. Cleared by
     * succeeding, or by asking from anywhere else.
     */
    private @Nullable BlockPos failedCell;
    private int failedStreak;

    public AgentRiser(AgentBody person) {
        this.person = person;
    }

    @Override
    public boolean up(String itemId) {
        if (state == RiseState.RISING) {
            return false;
        }
        if (person.inventory().count(itemId) <= 0) {
            return false;
        }
        Level level = person.level();
        BlockPos feet = person.blockPosition();
        if (!feet.equals(failedCell)) {
            failedCell = null; // asked from somewhere else — a different spot is a different problem
            failedStreak = 0;
        } else if (failedStreak >= ATTEMPTS_PER_CELL) {
            return false; // stop feeding the loop
        }
        BlockPos headroom = feet.above(2);
        if (!level.getBlockState(headroom).getCollisionShape(level, headroom).isEmpty()
                || !level.getBlockState(feet).canBeReplaced()) {
            return false; 
        }
        this.base = feet;
        this.itemId = itemId;
        this.ticks = 0;
        this.centringTicks = 0;
        this.centring = !centred(feet);
        this.state = RiseState.RISING;
        log("step up", "from " + feet.toShortString() + (this.centring ? " (centring first)" : ""));
        return true;
    }

    /**
     * True when the box sits wholly inside {@code cell} — the stance the single-column headroom
     * check in {@link #up} assumes. Per axis, because the box is axis-aligned.
     */
    private boolean centred(BlockPos cell) {
        return Math.abs(person.entity().getX() - (cell.getX() + 0.5)) <= CENTRE_TOLERANCE
                && Math.abs(person.entity().getZ() - (cell.getZ() + 0.5)) <= CENTRE_TOLERANCE;
    }

    /** One tick of rise work, from the body's server AI step; a no-op unless rising. */
    public void tick() {
        if (state != RiseState.RISING) {
            return;
        }
        if (centring && !stepToMiddle()) {
            return; // still shuffling (or the step just died trying) — no jump input this tick
        }
        person.faceBlock(base); 
        person.entity().setJumping(true); // held-space semantics: aiStep jumps them when grounded
        if (person.entity().getY() >= base.getY() + 1.0) {
            person.entity().setJumping(false);
            Level level = person.level();
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            if (!(item instanceof BlockItem blockItem)
                    || !level.getBlockState(base).canBeReplaced()
                    || person.inventory().count(itemId) <= 0) {
                fail("mid-air, cell or item gone at " + base.toShortString());
                return;
            }
            BlockState blockState = blockItem.getBlock().defaultBlockState();
            level.setBlockAndUpdate(base, blockState);
            level.gameEvent(GameEvent.BLOCK_PLACE, base,
                    GameEvent.Context.of(person.entity(), blockState));
            SoundType sound = blockState.getSoundType();
            level.playSound(null, base, sound.getPlaceSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
            person.entity().swing(InteractionHand.MAIN_HAND);
            person.inventory().remove(itemId, 1);
            state = RiseState.RISEN;
            failedCell = null; // the cell yielded — whatever was wrong with it is over
            failedStreak = 0;
            log("placed", itemId + " at " + base.toShortString());
            return;
        }
        if (++ticks > STEP_TIMEOUT_TICKS) {
            fail("never cleared block height above " + base.toShortString());
        }
    }

    /**
     * One tick of the centring shuffle toward the middle of the feet cell (see the class doc).
     * Returns true the moment the body is centred, so the jump goes on that same tick.
     */
    private boolean stepToMiddle() {
        if (centred(base)) {
            centring = false;
            return true;
        }
        if (++centringTicks > CENTRE_TIMEOUT_TICKS) {
            fail("never reached the middle of " + base.toShortString());
            return false;
        }
        double dx = base.getX() + 0.5 - person.entity().getX();
        double dz = base.getZ() + 0.5 - person.entity().getZ();
        // Minecraft yaw: 0 faces +Z and increases clockwise, so facing a point is atan2(dz, dx)
        // offset by -90 — the Navigator's convention. driveForward also clears the jump input.
        person.driveForward((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F, CENTRE_THROTTLE);
        return false;
    }

    /**
     * Kill the step in flight, remembering the cell so {@link #up} can stop retrying it after
     * {@link #ATTEMPTS_PER_CELL}.
     */
    private void fail(String detail) {
        person.entity().setJumping(false);
        centring = false;
        state = RiseState.FAILED;
        if (base.equals(failedCell)) {
            failedStreak++;
        } else {
            failedCell = base;
            failedStreak = 1;
        }
        log("step failed", detail);
        if (failedStreak >= ATTEMPTS_PER_CELL) {
            // up() just returns false, and a silent give-up makes the next stuck body invisible
            // in the journal.
            log("step gave up", failedStreak + " dead steps from " + base.toShortString()
                    + " — refusing further rises from this cell");
        }
    }

    private void log(String event, String detail) {
        person.journal().record(Category.BODY, "rise " + event, detail);
    }

    @Override
    public RiseState state() {
        return state;
    }

    @Override
    public void abort() {
        if (state == RiseState.RISING) {
            person.entity().setJumping(false);
        }
        centring = false;
        state = RiseState.IDLE;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * A step in progress, and where the last one died. A chop that survives a reload comes back
     * with {@code riseIssued} set, so a forgotten riser leaves it waiting on a climb nobody is
     * climbing; the failed-cell streak rides along for the reason its field note gives.
     */
    public record Step(String state, @Nullable BlockPos base, @Nullable String itemId, int ticks,
                       int centringTicks, boolean centring, @Nullable BlockPos failedCell,
                       int failedStreak) {
    }

    /** What this riser would need to finish the step it was making. */
    public Step snapshot() {
        return new Step(state.name(), base, itemId, ticks, centringTicks, centring, failedCell,
                failedStreak);
    }

    /** Puts a step back, mid-climb. */
    public void restore(Step step) {
        this.state = RiseState.valueOf(step.state());
        this.base = step.base();
        this.itemId = step.itemId();
        this.ticks = step.ticks();
        this.centringTicks = step.centringTicks();
        this.centring = step.centring();
        this.failedCell = step.failedCell();
        this.failedStreak = step.failedStreak();
    }
}
