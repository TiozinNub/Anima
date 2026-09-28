package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.nav.WorldSnapshot;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.nav.Navigator;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * When a body asks how the space it stands in opens, and the answer it last got (spec:
 * {@code 2026-09-28-shelter-design.md}).
 *
 * <p>Asked when a walk ends, over that walk's own capture; once after loading; when the body stands
 * somewhere its answer does not cover without having walked there (teleported, pushed); when a door
 * on the space's edge is not as the answer found it; and when something aggressive is about and
 * the answer is old. Never while walking, since the answer would be about somewhere already left.
 * One question is out at a time, and a reason to ask again while it is out asks once it is back.
 */
final class EnclosureWatch {

    /** How old an answer may be when a threat turns up before it is asked again: 5 s. */
    static final int STALE_TICKS = 100;

    /** How often the doors on a space's edge are looked at. */
    private static final int DOOR_LOOK_TICKS = 10;

    private final AgentBody body;
    private Enclosure verdict = Enclosure.UNKNOWN;
    /** The grid the verdict was worked out on — its doors are what a live door is compared with. */
    private @Nullable NavGrid checkedOn;
    private @Nullable CompletableFuture<Enclosure> pending;
    private @Nullable NavGrid pendingOn;
    /** Something asked for a fresh look while one was out. */
    private boolean askAgain;
    private boolean loaded;
    private Navigator.State lastState = Navigator.State.IDLE;
    private long doorsLookedAt;
    /** Where the last question was asked from: a body its answer misses is asked about once. */
    private @Nullable Pos askedFrom;

    EnclosureWatch(AgentBody body) {
        this.body = body;
    }

    /** The answer, if it still covers where the body stands; {@link Enclosure#UNKNOWN} if not. */
    Enclosure current() {
        if (!this.verdict.known() || walking()) {
            return Enclosure.UNKNOWN;
        }
        Pos feet = feet();
        if (this.verdict.openness() == Enclosure.Openness.OPEN) {
            Pos from = this.verdict.from();
            return from != null && Math.abs(from.x() - feet.x()) <= 1
                    && Math.abs(from.y() - feet.y()) <= 1 && Math.abs(from.z() - feet.z()) <= 1
                    ? this.verdict : Enclosure.UNKNOWN;
        }
        return this.verdict.contains(feet) ? this.verdict : Enclosure.UNKNOWN;
    }

    /** One look, from the brain's tick. */
    void tick(List<Being> beings) {
        if (!(this.body.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        adoptIfBack();
        Navigator navigator = this.body.navigator();
        Navigator.State state = navigator.state();
        boolean walkEnded = moving(this.lastState) && !moving(state);
        this.lastState = state;
        if (moving(state)) {
            return;
        }
        if (walkEnded) {
            ask(level, navigator.plannedGrid());
        } else if (!this.loaded) {
            // In the tick: a reload must look like two ticks in a row, and the answer this body had
            // is not saved — it is a reading of the world, so it is read again.
            askNow(level);
        } else if (!current().known() && this.pending == null && !feet().equals(this.askedFrom)) {
            ask(level, null);
        } else if (doorsChanged(level, now)) {
            ask(level, null);
        } else if (threatened(beings) && now - this.verdict.at() > STALE_TICKS) {
            ask(level, null);
        }
        this.loaded = true;
    }

    private void ask(ServerLevel level, @Nullable NavGrid walked) {
        dispatch(level, walked, false);
    }

    private void askNow(ServerLevel level) {
        dispatch(level, null, true);
    }

    private void dispatch(ServerLevel level, @Nullable NavGrid walked, boolean inTick) {
        if (this.pending != null) {
            this.askAgain = true;
            return;
        }
        this.askedFrom = feet();
        PathfinderService.EnclosureDispatch dispatch = PathfinderService.enclosure(level,
                this.body.blockPosition(), MoveCapabilities.of(this.body.profile()), walked,
                inTick);
        this.pending = dispatch.result();
        this.pendingOn = dispatch.grid();
        adoptIfBack();
    }

    private void adoptIfBack() {
        CompletableFuture<Enclosure> out = this.pending;
        if (out == null || !out.isDone()) {
            return;
        }
        Enclosure answer;
        try {
            answer = out.getNow(null);
        } catch (RuntimeException failed) {
            // A search that threw is a bug to see, not a reason to stop asking.
            AnimaMod.LOGGER.warn("enclosure check failed", failed);
            answer = null;
        }
        this.pending = null;
        if (answer != null) {
            if (!answer.sameAs(this.verdict) || !this.verdict.known()) {
                this.body.journal().record(Category.SENSE, "enclosure", answer.describe());
            }
            this.verdict = answer;
            this.checkedOn = this.pendingOn;
        }
        this.pendingOn = null;
        if (this.askAgain) {
            this.askAgain = false;
            if (this.body.level() instanceof ServerLevel level && !moving(this.lastState)) {
                ask(level, null);
            }
        }
    }

    /**
     * Whether a door on the space's edge now stands otherwise than the grid the answer was worked
     * on had it — swung by anybody, or broken. Two block reads a door, every half second.
     */
    private boolean doorsChanged(ServerLevel level, long now) {
        NavGrid grid = this.checkedOn;
        if (grid == null || this.verdict.doors().isEmpty() || now - this.doorsLookedAt < DOOR_LOOK_TICKS) {
            return false;
        }
        this.doorsLookedAt = now;
        for (Pos door : this.verdict.doors()) {
            BlockPos pos = new BlockPos(door.x(), door.y(), door.z());
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (WorldSnapshot.classifyAt(level, pos) != grid.cell(door.x(), door.y(), door.z())
                    || WorldSnapshot.doorwayAt(level, pos)
                            != grid.doorway(door.x(), door.y(), door.z())) {
                return true;
            }
        }
        return false;
    }

    private static boolean threatened(List<Being> beings) {
        for (Being being : beings) {
            if (being.aggressive()) {
                return true;
            }
        }
        return false;
    }

    private boolean walking() {
        return moving(this.body.navigator().state());
    }

    private static boolean moving(Navigator.State state) {
        return state == Navigator.State.PATHING || state == Navigator.State.FOLLOWING;
    }

    private Pos feet() {
        BlockPos at = this.body.blockPosition();
        return new Pos(at.getX(), at.getY(), at.getZ());
    }
}
