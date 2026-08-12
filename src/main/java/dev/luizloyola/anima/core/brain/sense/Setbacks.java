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
        STRAYED(0.3);

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
     * What the search should be told, right now — an immutable snapshot with every entry's age
     * already priced in. Empty (and free to consult) for a body that has not been having trouble,
     * which is nearly all of them nearly all of the time.
     */
    public SetbackField field(long now) {
        prune(now);
        if (this.entries.isEmpty()) {
            return SetbackField.NONE;
        }
        List<SetbackField.Source> sources = new ArrayList<>(this.entries.size());
        for (Setback entry : this.entries) {
            double weight = entry.kind().weight() * entry.strength() * fade(now - entry.tick());
            if (weight > 0.0) {
                sources.add(new SetbackField.Source(entry.at(), entry.kind(), weight));
            }
        }
        return sources.isEmpty() ? SetbackField.NONE : new SetbackField(sources);
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
    }

    /** Whether anything is remembered at all (without pruning — a cheap, approximate reading). */
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /** One line for the debug readout: how much trouble, and the worst of it. */
    public String describe(long now) {
        prune(now);
        if (this.entries.isEmpty()) {
            return "nothing lately";
        }
        Setback worst = this.entries.get(0);
        for (Setback entry : this.entries) {
            if (entry.strength() > worst.strength()) {
                worst = entry;
            }
        }
        return this.entries.size() + " place(s), worst "
                + worst.kind().name().toLowerCase(java.util.Locale.ROOT) + " ×" + worst.strength()
                + " at (" + worst.at().x() + ", " + worst.at().y() + ", " + worst.at().z() + ")";
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Everything remembered, for the save. Ages are absolute ticks, so they keep fading correctly. */
    public List<Setback> snapshot() {
        return List.copyOf(this.entries);
    }

    public void restore(List<Setback> saved) {
        this.entries.clear();
        this.entries.addAll(saved);
    }
}
