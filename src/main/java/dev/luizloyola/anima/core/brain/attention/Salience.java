package dev.luizloyola.anima.core.brain.attention;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What makes a thing worth looking at — the vocabulary {@link Attention} scores its candidates in,
 * and the seam a consumer teaches its own answers through.
 *
 * <p><b>Sources, not behaviours.</b> Behaviours would each have to know when the others should win,
 * a rule that needs editing every time a fifth arrives; a source only answers "how much is this
 * worth looking at", and the picker never grows.
 *
 * <p><b>Stateless and shared.</b> One instance answers for every agent in the world, so nothing
 * here may remember anything: the per-body memory lives in that body's {@link Attention} and
 * reaches a source through {@link Scene}.
 */
public final class Salience {

    /**
     * Everything a source is allowed to know.
     *
     * @param eyeX where the eyes are, world X — candidates are scored against this, not the feet
     * @param eyeY eye height
     * @param eyeZ eye position, world Z
     * @param bodyYaw which way the shoulders are squared (Minecraft convention: 0° is +Z)
     * @param now the game tick
     * @param percepts what this body currently perceives — beings, drops, blocks
     * @param knowledge what it remembers of places
     * @param danger how frightening it finds a species — what turns a noise into a startle
     * @param profile what this body is like; a source reads reach and aperture from here rather
     *     than inventing a radius
     * @param seen what this body has looked at lately, and how often — for {@link #novelty}
     */
    public record Scene(double eyeX, double eyeY, double eyeZ, double bodyYaw, long now,
                        Percepts percepts, AgentKnowledge knowledge, DangerTable danger,
                        AgentProfile profile, Map<String, Seen> seen) {

        /**
         * How fresh a look at {@code key} would be, given the thing is doing {@code state}: 1 for
         * something never looked at, climbing back to 1 over a refractory that GROWS each time this
         * body looks at the same thing doing the same thing.
         *
         * <p>A flat refractory stops a stare but not a body returning to the same neighbour every
         * five seconds all afternoon, so boredom accumulates: the wait doubles, trebles, quadruples
         * up to {@link Attention#HABITUATION_CAP}, settling at a glance about once every half
         * minute.
         *
         * <p><b>Doing something else resets it</b> — a body is bored of the sight, not the person.
         */
        public double novelty(String key, String state) {
            Seen last = seen.get(key);
            if (last == null) {
                return 1.0;
            }
            // A change resets the accumulated boredom but not the clock: otherwise anything
            // twitching between two states would be fully novel on every flip.
            double repeats = last.state().equals(state)
                    ? Math.min(Attention.HABITUATION_CAP, 1 + last.looks())
                    : 1.0;
            return Math.min(1.0,
                    Math.max(0.0, (now - last.at()) / (Attention.REFRACTORY_TICKS * repeats)));
        }

        /** Straight-line distance from the eyes to a world point. */
        public double distanceTo(double x, double y, double z) {
            double dx = x - eyeX;
            double dy = y - eyeY;
            double dz = z - eyeZ;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /**
         * 1 underfoot, 0 at the edge of perception. Linear on purpose — an inverse-square falloff
         * scores everything past a few blocks the same tiny number, leaving novelty to decide.
         */
        public double nearness(double distance) {
            double reach = profile.i(dev.luizloyola.anima.core.agent.ProfileAspect.SENSES_RADIUS);
            return Math.max(0.0, 1.0 - distance / reach);
        }
    }

    /**
     * What this body remembers about looking at one thing.
     *
     * @param at the tick the last look at it ended
     * @param looks how many looks in a row landed on it doing the same thing — the boredom
     * @param state what it was doing when last looked at; a different answer now means the count
     *     starts over, because a body tires of a sight rather than of a thing
     */
    public record Seen(long at, int looks, String state) {
    }

    /**
     * One thing worth looking at, priced.
     *
     * @param key what this candidate is, stably across ticks — {@code being:<uuid>},
     *     {@code drop:12,64,-8}. Two proposals of the same thing must agree on it, or a body will
     *     look at its own last glance forever
     * @param x where to look, world X (a point, not a cell — eyes aim at points)
     * @param y where to look, world Y
     * @param z where to look, world Z
     * @param score how much this is worth looking at, against {@link Attention#SCAN_SCORE} as the
     *     floor of caring at all
     * @param dwell how long to hold it, in ticks
     * @param snap whether the head should whip round rather than turn — a startle, and nothing else
     * @param reason one phrase for the readout, in the body's own terms ("a noise", "Alice")
     * @param state what this thing is DOING, as a short string that changes when the sight does.
     *     Only boredom reads it (see {@link Scene#novelty}); something that never changes passes a
     *     constant
     */
    public record Candidate(String key, double x, double y, double z, double score, int dwell,
                            boolean snap, String reason, String state) {
    }

    /** Something with an opinion about what is worth looking at. Stateless; see the class note. */
    public interface Source {

        /** Everything this source thinks is worth a look right now, priced. May be empty. */
        List<Candidate> propose(Scene scene);

        /**
         * Whether {@code key} is one this source minted, and so whether it should be asked to
         * {@link #track} it. Defaults to no: a dropped item and a remembered place do not move, so
         * a body keeps aiming where it aimed.
         */
        default boolean owns(String key) {
            return false;
        }

        /**
         * Where {@code key} is NOW — how a body keeps its eyes on somebody walking past rather than
         * on the spot they were standing in.
         *
         * <p>Asked every tick for a focus this source {@link #owns}, so it must be cheap. An empty
         * answer means <b>gone</b> and the body drops the look.
         */
        default Optional<Candidate> track(Scene scene, String key) {
            return Optional.empty();
        }

        /** For readouts and for the registry's own error messages. */
        String name();
    }

    private Salience() {
    }
}
