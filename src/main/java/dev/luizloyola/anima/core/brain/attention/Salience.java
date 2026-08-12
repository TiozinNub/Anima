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
     * Everything a source is allowed to know: where the eyes are, what the body perceives, what it
     * remembers, what frightens it, and what it has been looking at lately.
     *
     * @param eyeX where the eyes are, world X — every candidate is scored against this, not
     *     against the feet, because a look is a line from an eye
     * @param eyeY eye height
     * @param eyeZ eye position, world Z
     * @param bodyYaw which way the shoulders are squared (Minecraft convention: 0° is +Z)
     * @param now the game tick
     * @param percepts what this body currently perceives — beings, drops, blocks
     * @param knowledge what it remembers of places
     * @param danger how frightening it finds a species — what turns a noise into a startle
     * @param profile what this body is like; a source reads reach and aperture from here rather
     *     than inventing a radius, so a wolf's attention is a wolf's
     * @param lastLookedAt when each key was last looked away from, for {@link #novelty}
     */
    public record Scene(double eyeX, double eyeY, double eyeZ, double bodyYaw, long now,
                        Percepts percepts, AgentKnowledge knowledge, DangerTable danger,
                        AgentProfile profile, Map<String, Long> lastLookedAt) {

        /**
         * How fresh a look at {@code key} would be: 1 for something never looked at, climbing back
         * to 1 over {@link Attention#REFRACTORY_TICKS} after the last look at it ended.
         *
         * <p>This is what stops a stare — without it the highest-scoring thing in sight would win
         * every decision for as long as it is there.
         */
        public double novelty(String key) {
            Long last = lastLookedAt.get(key);
            if (last == null) {
                return 1.0;
            }
            return Math.min(1.0, Math.max(0.0, (now - last) / (double) Attention.REFRACTORY_TICKS));
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
     * One thing worth looking at, priced.
     *
     * @param key what this candidate is, stably across ticks — {@code being:<uuid>},
     *     {@code drop:12,64,-8}. The handle novelty is remembered by and pursuit re-finds it by, so
     *     two proposals of the same thing must agree on it or a body will look at its own last
     *     glance forever
     * @param x where to look, world X (a point, not a cell — eyes aim at points)
     * @param y where to look, world Y
     * @param z where to look, world Z
     * @param score how much this is worth looking at, against {@link Attention#SCAN_SCORE} as the
     *     floor of caring at all
     * @param dwell how long to hold it, in ticks
     * @param snap whether the head should whip round rather than turn — a startle, and nothing else
     * @param reason one phrase for the readout, in the body's own terms ("a noise", "Alice")
     */
    public record Candidate(String key, double x, double y, double z, double score, int dwell,
                            boolean snap, String reason) {
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
