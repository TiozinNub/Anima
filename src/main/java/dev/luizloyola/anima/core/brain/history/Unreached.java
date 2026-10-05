package dev.luizloyola.anima.core.brain.history;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cells this body's legs lately found no way to: a walk there failed stranded or unreachable, or
 * {@link #STALLS_TO_STRIKE} in a row stalled or wedged. Struck for a while, so a stand chooser
 * picks another instead of re-ordering the same walk: a stand on a roof was searched for 26,577
 * times in four minutes (forest, 2026-10-02).
 *
 * <p>Body state rather than a task's: each fresh round of an achieve-goal builds its subgoals new,
 * so a strike held by one would be forgotten by the next.
 */
public final class Unreached {

    /**
     * Two minutes: past the 600-tick retry cooldown of a failed errand, so the retry finds the stand
     * still struck and fails without a walk; short enough that a door opened since is not held
     * against the place for long.
     */
    public static final long LIFETIME_TICKS = 2_400;
    /**
     * Above the 49 cells a new store's stands are drawn from (two rings of ground and the cells
     * beside them): with fewer, the oldest strike went before the last stand was tried, and the
     * walks went round again.
     */
    public static final int CAPACITY = 64;
    /**
     * Walks in a row that must stall or wedge at a cell before it is struck: one is a body or a
     * sheep in the way, and the legs have already retried before they say so.
     */
    public static final int STALLS_TO_STRIKE = 3;
    /**
     * How long a cell walks keep stalling at stays struck: a minute, still past a failed errand's
     * retry cooldown, but half a strike outright, for a stall says less than a missing way does.
     */
    public static final long STALL_LIFETIME_TICKS = 1_200;

    /**
     * @param stalls walks in a row that stalled or wedged here, counted from the {@code tick} of the
     *               last; 0 for a cell struck outright
     */
    public record Strike(Pos cell, long tick, int stalls) {
        public Strike(Pos cell, long tick) {
            this(cell, tick, 0);
        }

        boolean holds(long now) {
            return stalls == 0 ? now - tick < LIFETIME_TICKS
                    : stalls >= STALLS_TO_STRIKE && now - tick < STALL_LIFETIME_TICKS;
        }
    }

    private final List<Strike> strikes = new ArrayList<>();

    public void strike(Pos cell, long now) {
        strikes.removeIf(strike -> strike.cell().equals(cell));
        strikes.add(0, new Strike(cell, now));
        prune(now);
    }

    /**
     * A walk here stalled or wedged. Counted, not struck, until {@link #STALLS_TO_STRIKE} have in a
     * row; a count left alone for {@link #LIFETIME_TICKS} starts again.
     */
    public void stalled(Pos cell, long now) {
        prune(now);
        Strike prior = strikes.stream().filter(strike -> strike.cell().equals(cell)).findFirst()
                .orElse(null);
        if (prior != null && prior.stalls() == 0) {
            return; // struck outright already, and for longer
        }
        strikes.remove(prior);
        strikes.add(0, new Strike(cell, now, prior == null ? 1 : prior.stalls() + 1));
        prune(now);
    }

    /** A walk got there after all. */
    public void clear(Pos cell) {
        strikes.removeIf(strike -> strike.cell().equals(cell));
    }

    public boolean struck(Pos cell, long now) {
        for (Strike strike : strikes) {
            if (strike.cell().equals(cell) && strike.holds(now)) {
                return true;
            }
        }
        return false;
    }

    /** The saved form, newest first. */
    public List<Strike> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(strikes));
    }

    public void restore(List<Strike> saved) {
        strikes.clear();
        strikes.addAll(saved);
        while (strikes.size() > CAPACITY) {
            strikes.remove(strikes.size() - 1);
        }
    }

    private void prune(long now) {
        strikes.removeIf(strike -> now - strike.tick() >= LIFETIME_TICKS);
        while (strikes.size() > CAPACITY) {
            strikes.remove(strikes.size() - 1);
        }
    }
}
