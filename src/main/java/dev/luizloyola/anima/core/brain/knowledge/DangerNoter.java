package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * Turning a fright into a place. A threat lingers in the being sense for seconds — right for "is
 * something dangerous next to me right now", wrong for everything after — so it is written down as
 * a place and gets the ordinary memory machinery: staleness by age, eviction, persistence, the
 * knowledge viewer.
 *
 * <p><b>The weight is not stored.</b> {@code detail} carries the species and the weight is read
 * from the {@link DangerTable} on every use, so retuning a creeper retunes every remembered
 * creeper. A faceless attacker files under {@link DangerTable#HOSTILE_KEY}, a real key, and prices
 * the same way.
 *
 * <p><b>Memories follow their owner.</b> {@code individual} is the being's id, so a wandering
 * skeleton updates one memory instead of a trail of ghosts. Matched here rather than by the store's
 * merge radius, as in {@code HerdNoter}: the rule is about identity, not distance.
 */
public final class DangerNoter {

    /**
     * How near a remembered danger must be before its absence counts as evidence against it.
     *
     * <p>Much shorter than perception: sight is a 150-degree cone, so at notice range
     * "I do not perceive it" mostly means "I am facing the other way". This close, every channel
     * covers the spot at once and absence is a real observation rather than an angle.
     */
    private static final int ABSENCE_RADIUS = 6;

    private DangerNoter() {
    }

    /**
     * One noting beat: write down what is frightening right now, and drop what has been disproven.
     * Returns the events worth narrating — empty on the common nothing-new beat.
     */
    public static List<SenseEvent> note(DangerTable danger, Pos observer, List<Being> beings,
                                        AgentKnowledge knowledge, long now, int maxPerKind) {
        List<SenseEvent> events = new ArrayList<>();
        forgetTheDisproven(danger, observer, beings, knowledge, events);
        for (Being being : beings) {
            if (!frightening(danger, being)) {
                continue;
            }
            PoiMemory existing = remembered(knowledge, being);
            // A fright the body can no longer sense is not re-stamped: its memory ages from the
            // last time it was really there, not through the sense's whole linger. With no memory
            // yet, write one now — a creeper glimpsed for a moment is what most needs remembering
            // (caught live: one perceived, fled from, never recorded).
            if (being.awareness() == Being.Awareness.REMEMBERED && existing != null) {
                continue;
            }
            if (existing != null) {
                knowledge.forget(PoiKind.DANGER, existing.anchor()); // moved, not duplicated
            }
            PoiMemory memory = new PoiMemory(PoiKind.DANGER, keyFor(being), being.id().value(),
                    being.pos(), Region.of(being.pos()), 1, false, now);
            knowledge.note(memory, maxPerKind);
            events.add(SenseEvent.noted(memory));
        }
        return events;
    }

    /**
     * Drops a remembered danger the body is now close enough to see is not there.
     *
     * <p>Time is not the only thing that ends a fear: an empty clearing is evidence, the same
     * evidence a remembered tree gets when somebody has felled it, and it is answered the same way
     * — forgotten at once rather than left to decay, so no-go areas do not accumulate.
     */
    private static void forgetTheDisproven(DangerTable danger, Pos observer, List<Being> beings,
                                           AgentKnowledge knowledge, List<SenseEvent> events) {
        for (PoiMemory existing : List.copyOf(knowledge.sighted(PoiKind.DANGER))) {
            if (chebyshev(existing.anchor(), observer) > ABSENCE_RADIUS) {
                continue; // too far to be evidence of anything
            }
            boolean stillThere = false;
            for (Being being : beings) {
                // LIVE perception only. A track the sense is merely remembering is the same belief
                // by a second route; letting it vouch for itself would disprove nothing, ever.
                if (frightening(danger, being)
                        && being.awareness() != Being.Awareness.REMEMBERED
                        && being.id().value().equals(existing.individual())) {
                    stillThere = true;
                    break;
                }
            }
            if (!stillThere) {
                knowledge.forget(PoiKind.DANGER, existing.anchor());
                events.add(SenseEvent.forgot(PoiKind.DANGER, existing.anchor()));
            }
        }
    }

    /** Whether this being is worth remembering the location of at all. */
    private static boolean frightening(DangerTable danger, Being being) {
        return being.aggressive() && danger.weight(keyFor(being)) > 0.0;
    }

    /** This body's existing memory of that particular thing, or null. */
    private static PoiMemory remembered(AgentKnowledge knowledge, Being being) {
        for (PoiMemory memory : List.copyOf(knowledge.sighted(PoiKind.DANGER))) {
            if (being.id().value().equals(memory.individual())) {
                return memory;
            }
        }
        return null;
    }

    /** What the table should be asked about this being — its species, or the anonymous key. */
    public static String keyFor(Being being) {
        return being.species().isEmpty() ? DangerTable.HOSTILE_KEY : being.species();
    }

    private static int chebyshev(Pos a, Pos b) {
        return Math.max(Math.abs(a.x() - b.x()),
                Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
    }
}
