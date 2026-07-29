package dev.luizloyola.anima.core.brain.sense;

import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * How bad it would be to stand somewhere — one number per place, over everything a body knows to
 * be afraid of.
 *
 * <p>A field, not a direction: fleeing the weighted centre of what is visible points the escape
 * vector into the gap between two threats, and a settler fled two mobs straight into a creeper
 * that way.
 *
 * <dl>
 *   <dt>Species</dt><dd>the {@link DangerTable} weight, argued about in the operator's file.</dd>
 *   <dt>Age</dt><dd>a remembered fright fades linearly over {@link #FADE_TICKS}; live perception
 *       never fades.</dd>
 *   <dt>Distance</dt><dd>inverse-square, floored so standing on it is not infinite, cut off at
 *       {@link #REACH}.</dd>
 * </dl>
 *
 * <p>Pure core, and an immutable snapshot — which is what makes it safe to hand to the off-thread
 * pathfinder.
 */
public final class DangerField {

    /** A remembered fright is worth nothing after five minutes of game time. */
    public static final int FADE_TICKS = 6_000;

    /** Beyond this many blocks a danger contributes nothing — the field stays a local question. */
    public static final double REACH = 24.0;

    /** Distance floor, so a danger underfoot is a large number rather than an infinite one. */
    private static final double MIN_DISTANCE = 1.0;

    /** Nothing to be afraid of anywhere — what a body with no knowledge and no percepts has. */
    public static final DangerField NONE = new DangerField(List.of());

    /** One thing worth avoiding: where it is, and how much it is worth right now. */
    public record Source(Pos at, String species, double weight) {
    }

    private final List<Source> sources;

    private DangerField(List<Source> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * The field a body carries: everything it can see that frightens it, plus everything it
     * remembers frightening it, aged.
     *
     * @param maxAge how stale a memory may be before it is ignored entirely — normally
     *     {@link #FADE_TICKS}; a caller with a shorter horizon may pass less
     */
    public static DangerField of(DangerTable table, Collection<Being> perceived,
                                 AgentKnowledge knowledge, long now, int maxAge) {
        List<Source> sources = new ArrayList<>();
        for (Being being : perceived) {
            if (!being.aggressive()) {
                continue;
            }
            String species = being.species().isEmpty() ? DangerTable.HOSTILE_KEY : being.species();
            double weight = table.weight(species);
            if (weight > 0.0) {
                sources.add(new Source(being.pos(), species, weight));
            }
        }
        for (PoiMemory memory : knowledge.all(PoiKind.DANGER)) {
            double weight = table.weight(memory.detail()) * fade(now - memory.lastSeenTick(), maxAge);
            if (weight > 0.0) {
                sources.add(new Source(memory.anchor(), memory.detail(), weight));
            }
        }
        return sources.isEmpty() ? NONE : new DangerField(sources);
    }

    /** How much a fright of this age is still worth: full when fresh, nothing once faded out. */
    private static double fade(long age, int maxAge) {
        if (age <= 0) {
            return 1.0;
        }
        if (age >= maxAge) {
            return 0.0;
        }
        return 1.0 - (double) age / maxAge;
    }

    /** Whether there is anything at all to weigh — the common case is no, and it is free. */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** Everything contributing, for a readout that wants to say why somewhere is frightening. */
    public List<Source> sources() {
        return sources;
    }

    /**
     * How frightening this place is. Zero when nothing known reaches it, which is the answer
     * almost everywhere and costs almost nothing to get.
     */
    public double at(int x, int y, int z) {
        double total = 0.0;
        for (Source source : sources) {
            double dx = source.at().x() - x;
            double dy = source.at().y() - y;
            double dz = source.at().z() - z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > REACH * REACH) {
                continue;
            }
            double distance = Math.max(MIN_DISTANCE, Math.sqrt(distanceSq));
            total += source.weight() / (distance * distance);
        }
        return total;
    }

    /** {@link #at(int, int, int)} at a cell. */
    public double at(Pos pos) {
        return at(pos.x(), pos.y(), pos.z());
    }

    /** The worst thing reaching this place, for a readout — or empty when nothing does. */
    public java.util.Optional<Source> worstAt(int x, int y, int z) {
        Source worst = null;
        double best = 0.0;
        for (Source source : sources) {
            double dx = source.at().x() - x;
            double dy = source.at().y() - y;
            double dz = source.at().z() - z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > REACH * REACH) {
                continue;
            }
            double contribution =
                    source.weight() / Math.max(MIN_DISTANCE * MIN_DISTANCE, distanceSq);
            if (contribution > best) {
                best = contribution;
                worst = source;
            }
        }
        return java.util.Optional.ofNullable(worst);
    }

    @Override
    public String toString() {
        return "DangerField(" + sources.size() + " source(s))";
    }
}
