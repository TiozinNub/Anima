package dev.luizloyola.anima.core.brain.board;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * One person's view of the shared work-site claims ({@link SiteClaims}) — the identity is bound in
 * so tasks never juggle their own {@code AgentId}, mirroring {@code AgentJournal}. Callers stamp
 * the game time they already read this tick; the view keeps no clock.
 *
 * <p>A claim is GROUP state, but the task layer honours it: a method skips sites that are not
 * {@link #availableTo available}, a working task {@link #claim heartbeats} its site and
 * {@link #release releases} it on every exit.
 */
public interface AgentClaims {
    /**
     * Claim (or re-heartbeat) the site for this person until {@code now + }{@link
     * SiteClaims#ttlTicks()}. Returns {@code false} when someone else's live claim holds it —
     * in which case nothing changed and the site is not theirs to work.
     */
    boolean claim(PoiKind kind, Pos anchor, long now);

    /** Release the site if this person holds it; anyone else's claim is left untouched. */
    void release(PoiKind kind, Pos anchor);

    /** Whether the site is workable for this person: unclaimed, expired, or already theirs. */
    boolean availableTo(PoiKind kind, Pos anchor, long now);

    /**
     * The loner's view — claims always succeed, releases are no-ops. The default for a
     * {@link dev.luizloyola.anima.core.brain.BrainContext} assembled without a shared registry: a
     * person with no group is a group of one.
     */
    AgentClaims SOLO = new AgentClaims() {
        @Override
        public boolean claim(PoiKind kind, Pos anchor, long now) {
            return true;
        }

        @Override
        public void release(PoiKind kind, Pos anchor) {
        }

        @Override
        public boolean availableTo(PoiKind kind, Pos anchor, long now) {
            return true;
        }
    };
}
