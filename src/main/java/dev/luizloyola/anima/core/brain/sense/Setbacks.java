package dev.luizloyola.anima.core.brain.sense;

import java.util.ArrayList;
import java.util.List;

/**
 * Where this body has lately been beaten — its own short memory of trouble, so that trying again
 * means trying something <em>else</em>.
 *
 * <p>Body state, following {@code AgentRiser.failedCell}: a cell that keeps beating this body "is
 * a fact about this body's situation, not about whichever task happened to ask". It outlives the
 * order that discovered it, task churn, and a reload.
 *
 * <p>An {@code Instinct} sits out a fail-cooldown and a board item sits out its own; those pace a
 * <em>drive</em> and an <em>errand</em>, this one a <em>place</em>.
 *
 * <p>Mutable, server-thread only; the search gets an immutable {@link SetbackField} snapshot.
 *
 * <p>It forgets: entries fade linearly over {@link #LIFETIME_TICKS} and are dropped, and only
 * {@link #CAPACITY} are kept. Trouble that does not fade is a body slowly convincing itself the
 * world is impassable.
 */
public final class Setbacks {

    /**
     * How long one setback takes to fade to nothing — thirty seconds of game time.
     *
     * <p>Long enough for several cycles of "fail, cool down, have another go" (a re-path takes a
     * tick or two, a failed drive sits out a hundred ticks), short enough that a door somebody
     * opened is not held against the world for long. Not a config knob: it is tuned against the
     * fail-cooldown and the retry budget, and moving one of the three alone is how they stop
     * making sense together.
     */
    public static final int LIFETIME_TICKS = 600;

    /**
     * How many places are remembered at once. Trouble is local and recent by nature; a body that
     * needs more than this many grudges to get somewhere is a body with a bigger problem than a
     * cost field can express — which is what the confinement verdict is for.
     */
    public static final int CAPACITY = 16;

    /** What went wrong somewhere. Weights are relative, and their ordering is the whole claim. */
    public enum Kind {
        /**
         * Driven, and not moving at all — wedged on something the snapshot did not know about.
         * The strongest signal there is: the body was pushing and the world would not let it
         * through.
         */
        WEDGED(1.0),
        /**
         * On the plan and moving, but not arriving. Weaker, because plenty of innocent things look
         * like this from underneath — a current, a crowd, a slope taken slowly.
         */
        STALLED(0.6),
        /**
         * Found off the plan: shoved, dropped, a jump gone wrong. Weakest of the three. Where the
         * body ENDED up is only a guess at where the trouble was, and the guess is worth a lean
         * rather than a detour.
         */
        STRAYED(0.3),
        /**
         * A block the route meant to lay would not go. As firm as a wedge: the world said no to
         * this spot outright, and the next search should build somewhere else or not at all.
         */
        LAY_REFUSED(1.0);

        private final double weight;

        Kind(double weight) {
            this.weight = weight;
        }

        /** What one fresh setback of this kind is worth, before distance and fading. */
        public double weight() {
            return this.weight;
        }
    }

    /**
     * One remembered piece of trouble.
     *
     * @param at       the cell the body was standing in when it happened
     * @param kind     what went wrong
     * @param tick     when, so it can fade
     * @param strength how many times running this place has done it. A repeat bumps this instead
     *                 of adding an entry, which keeps {@link #CAPACITY} meaning what it says
     */
    public record Setback(Pos at, Kind kind, long tick, int strength) {
    }

    /**
     * Cap on {@link Setback#strength}. Past a handful of repeats the place is as discredited as it
     * is going to get, and letting the number climb forever would make one unlucky corner outweigh
     * everything a body ever learns afterwards.
     */
    private static final int MAX_STRENGTH = 4;

    private final List<Setback> entries = new ArrayList<>();

    /**
     * How long a move that hurt stays refused — one in-game day. Longer than a setback by far:
     * a setback is a lean, and a refused move is this body saying "not that way again". A day
     * because the world can change under it, and a bridge built over the gap should not be
     * refused for ever.
     */
    public static final int REFUSED_LIFETIME_TICKS = 24_000;
    /** How many refused moves are kept; the oldest goes first. */
    public static final int REFUSED_CAPACITY = 32;

    /**
     * A move this body was following when it got hurt: from the cell it set out from — a leap's
     * takeoff — to the cell it was making for. Refused outright, not priced: a fall that costs a
     * heart is not a detour's worth of trouble, it is a move this body cannot make (Luiz,
     * 2026-09-25).
     */
    public record RefusedMove(Pos from, Pos to, long tick) {
    }

    private final List<RefusedMove> refused = new ArrayList<>();

    /**
     * How long a stranded walk counts as evidence of being shut in — two minutes. Long enough for a
     * few jobs to be tried and fail; short enough that a pocket the body has left is forgotten.
     */
    public static final int STRANDED_WINDOW_TICKS = 2_400;
    /**
     * The most cells to stand in a stranded search may have found and still count: a pocket, not a
     * valley. Under the search's budget by a margin, so a search that stopped here ran out of
     * places, not of time — open ground exhausts the budget first and never counts. Cells to stand
     * in, not cells: a search from a ledge over a flooded channel closed 1,300 cells and found 3 of
     * them (2026-09-28).
     */
    public static final int POCKET_CELLS = 1_024;
    /** How close a walk must have started to where the body stands to be evidence about here. */
    public static final int POCKET_RADIUS = 12;
    /**
     * How many stranded walks from around one spot make it read as shut in. Walks, not different
     * places: a body in a crevice retried the one tree above it for good, each retry re-planned
     * from the same side, and open ground is kept out by {@link #POCKET_CELLS} — and the wider
     * survey that has to prove the suspicion — not by variety (2026-09-26).
     */
    public static final int STRANDED_WALKS = 3;
    /** How many are kept: a retry loop adds one every few seconds. */
    private static final int STRANDED_CAPACITY = 16;

    /** A walk that could not leave where it started, toward a place it could not reach. */
    public record Stranded(Pos from, Pos goal, long tick) {
    }

    private final List<Stranded> stranded = new ArrayList<>();

    /** Everything this store keeps, for the save. */
    public record State(List<Setback> entries, List<RefusedMove> refused, List<Stranded> stranded) {
        public State(List<Setback> entries, List<RefusedMove> refused) {
            this(entries, refused, List.of());
        }
    }

    /**
     * Remember that this place beat us. A repeat at the same cell refreshes the memory and
     * strengthens it instead of crowding the list; a different kind at the same cell takes the
     * cell over, because the newest news about a place is the truest.
     */
    public void record(Pos at, Kind kind, long now) {
        prune(now);
        for (int i = 0; i < this.entries.size(); i++) {
            Setback existing = this.entries.get(i);
            if (existing.at().equals(at)) {
                this.entries.set(i, new Setback(at, kind, now,
                        Math.min(MAX_STRENGTH, existing.strength() + 1)));
                return;
            }
        }
        if (this.entries.size() >= CAPACITY) {
            this.entries.remove(0); // oldest first: the list is kept in the order things happened
        }
        this.entries.add(new Setback(at, kind, now, 1));
    }

    /**
     * Whether {@code at} already holds a {@code kind} setback as strong as one gets, so recording it
     * again only refreshes it: a search asked from there is told what the last one was told.
     */
    public boolean spent(Pos at, Kind kind, long now) {
        prune(now);
        for (Setback entry : this.entries) {
            if (entry.at().equals(at)) {
                return entry.kind() == kind && entry.strength() >= MAX_STRENGTH;
            }
        }
        return false;
    }

    /**
     * Never again that move: this body got hurt following it. A repeat refreshes the day it is
     * refused for.
     */
    public void refuse(Pos from, Pos to, long now) {
        prune(now);
        this.refused.removeIf(move -> move.from().equals(from) && move.to().equals(to));
        if (this.refused.size() >= REFUSED_CAPACITY) {
            this.refused.remove(0);
        }
        this.refused.add(new RefusedMove(from, to, now));
    }

    /**
     * A walk from {@code from} failed stranded, its search having found {@code rest} cells to stand
     * in. Evidence of being shut in only from a small region: a search that found plenty of ground
     * is proof the body is not in a pocket, and clears what was gathered (Luiz, 2026-09-26: stranded
     * walks pile up and mark the area, roof or no roof, but never open ground).
     */
    public void stranded(Pos from, Pos goal, int rest, long now) {
        prune(now);
        if (rest > POCKET_CELLS) {
            this.stranded.clear();
            return;
        }
        if (this.stranded.size() >= STRANDED_CAPACITY) {
            this.stranded.remove(0);
        }
        this.stranded.add(new Stranded(from, goal, now));
    }

    /**
     * Whether the walks that failed from around {@code at} lately say it is shut in: at least
     * {@link #STRANDED_WALKS} stranded from within {@link #POCKET_RADIUS}. A suspicion, not a proof:
     * the confinement sense takes it as the reason to look wider, and a wider look that finds a way
     * out clears it.
     */
    public boolean enclosed(Pos at, long now) {
        prune(now);
        int near = 0;
        for (Stranded walk : this.stranded) {
            int dx = walk.from().x() - at.x();
            int dy = walk.from().y() - at.y();
            int dz = walk.from().z() - at.z();
            if (dx * dx + dy * dy + dz * dz <= POCKET_RADIUS * POCKET_RADIUS) {
                near++;
            }
        }
        return near >= STRANDED_WALKS;
    }

    /** The body is not shut in after all, or has got out: what was gathered no longer holds. */
    public void free() {
        this.stranded.clear();
    }

    /**
     * What the search should be told, right now — an immutable snapshot with every entry's age
     * already priced in. Empty (and free to consult) for a body that has not been having trouble,
     * which is nearly all of them nearly all of the time.
     */
    public SetbackField field(long now) {
        prune(now);
        if (this.entries.isEmpty() && this.refused.isEmpty()) {
            return SetbackField.NONE;
        }
        List<SetbackField.Source> sources = new ArrayList<>(this.entries.size());
        for (Setback entry : this.entries) {
            double weight = entry.kind().weight() * entry.strength() * fade(now - entry.tick());
            if (weight > 0.0) {
                sources.add(new SetbackField.Source(entry.at(), entry.kind(), weight));
            }
        }
        return sources.isEmpty() && this.refused.isEmpty()
                ? SetbackField.NONE
                : new SetbackField(sources, this.refused);
    }

    /** How much a setback of this age is still worth: full when fresh, nothing once faded out. */
    private static double fade(long age) {
        if (age <= 0) {
            return 1.0;
        }
        if (age >= LIFETIME_TICKS) {
            return 0.0;
        }
        return 1.0 - (double) age / LIFETIME_TICKS;
    }

    /** Drops everything that has finished fading. Called wherever the list is about to be used. */
    private void prune(long now) {
        this.entries.removeIf(entry -> now - entry.tick() >= LIFETIME_TICKS);
        this.refused.removeIf(move -> now - move.tick() >= REFUSED_LIFETIME_TICKS);
        this.stranded.removeIf(walk -> now - walk.tick() >= STRANDED_WINDOW_TICKS);
    }

    /** Whether anything is remembered at all (without pruning — a cheap, approximate reading). */
    public boolean isEmpty() {
        return this.entries.isEmpty() && this.refused.isEmpty() && this.stranded.isEmpty();
    }

    /** One line for the debug readout: how much trouble, and the worst of it. */
    public String describe(long now) {
        prune(now);
        if (this.entries.isEmpty()) {
            return this.refused.isEmpty() ? "nothing lately" : this.refused.size() + " move(s) refused";
        }
        Setback worst = this.entries.get(0);
        for (Setback entry : this.entries) {
            if (entry.strength() > worst.strength()) {
                worst = entry;
            }
        }
        return this.entries.size() + " place(s), worst "
                + worst.kind().name().toLowerCase(java.util.Locale.ROOT) + " ×" + worst.strength()
                + " at (" + worst.at().x() + ", " + worst.at().y() + ", " + worst.at().z() + ")"
                + (this.refused.isEmpty() ? "" : ", " + this.refused.size() + " move(s) refused");
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Everything remembered, for the save. Ages are absolute ticks, so they keep fading correctly. */
    public State snapshot() {
        return new State(List.copyOf(this.entries), List.copyOf(this.refused),
                List.copyOf(this.stranded));
    }

    public void restore(State saved) {
        this.entries.clear();
        this.entries.addAll(saved.entries());
        this.refused.clear();
        this.refused.addAll(saved.refused());
        this.stranded.clear();
        this.stranded.addAll(saved.stranded());
    }
}
