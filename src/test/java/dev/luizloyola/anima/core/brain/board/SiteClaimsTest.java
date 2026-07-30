package dev.luizloyola.anima.core.brain.board;

import dev.luizloyola.anima.core.brain.knowledge.TestPois;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.agent.AgentId;
import org.junit.jupiter.api.Test;

/**
 * The claim registry's contract: heartbeats not locks — a claim excludes others only while
 * fresh, re-claiming your own refreshes, releasing is holder-only, and a lapsed claim frees
 * the site without anyone's help (the dead-claimant story).
 */
class SiteClaimsTest {

    private final SiteClaims claims = new SiteClaims();
    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();
    private final Pos anchor = new Pos(10, 64, 10);

    @Test
    void aLiveClaimExcludesEveryoneElse() {
        assertTrue(claims.claim(TestPois.TREE, anchor, alice, 0));
        assertFalse(claims.claim(TestPois.TREE, anchor, bob, 1), "bob can't take alice's site");
        assertFalse(claims.availableTo(TestPois.TREE, anchor, bob, 1));
        assertTrue(claims.availableTo(TestPois.TREE, anchor, alice, 1), "theirs stays theirs");
        assertTrue(claims.claim(TestPois.TREE, anchor, alice, 1), "re-claiming your own succeeds");
    }

    @Test
    void aClaimIsAHeartbeatNotALock() {
        claims.claim(TestPois.TREE, anchor, alice, 0);
        long lastGasp = SiteClaims.ttlTicks() - 1;
        assertFalse(claims.availableTo(TestPois.TREE, anchor, bob, lastGasp), "still fresh");
        assertTrue(claims.availableTo(TestPois.TREE, anchor, bob, SiteClaims.ttlTicks()),
                "no heartbeat for a full TTL: the claimant is gone, the site frees itself");
        assertTrue(claims.claim(TestPois.TREE, anchor, bob, SiteClaims.ttlTicks()));
    }

    @Test
    void reclaimingRefreshesTheHeartbeat() {
        claims.claim(TestPois.TREE, anchor, alice, 0);
        claims.claim(TestPois.TREE, anchor, alice, 500); // the working tick's re-claim
        assertFalse(claims.availableTo(TestPois.TREE, anchor, bob, SiteClaims.ttlTicks() + 400),
                "the refresh moved the lapse out, not the original claim time");
    }

    @Test
    void releaseIsHolderOnly() {
        claims.claim(TestPois.TREE, anchor, alice, 0);
        claims.release(TestPois.TREE, anchor, bob); // not his to free
        assertFalse(claims.availableTo(TestPois.TREE, anchor, bob, 1));
        claims.release(TestPois.TREE, anchor, alice);
        assertTrue(claims.availableTo(TestPois.TREE, anchor, bob, 1), "a clean exit frees it now");
    }

    /**
     * The dump {@code /anima claims} prints: live holds only, soonest to die first. A lapsed entry
     * is no claim at all, and showing it would invent a state nobody can act on.
     */
    @Test
    void theDumpReportsLiveHoldsSoonestFirst() {
        Pos far = new Pos(80, 64, 80);
        claims.claim(TestPois.TREE, anchor, alice, 0);
        claims.claim(TestPois.TREE, far, bob, 50);

        var live = claims.held(60);
        assertEquals(2, live.size());
        assertEquals(alice, live.get(0).who(), "hers dies first, so hers is on top");
        assertEquals(SiteClaims.ttlTicks() - 60, live.get(0).remaining());
        assertEquals(bob, live.get(1).who());

        var later = claims.held(SiteClaims.ttlTicks() + 10);
        assertEquals(1, later.size(), "hers lapsed and is simply not listed");
        assertEquals(bob, later.get(0).who());
        assertTrue(claims.held(SiteClaims.ttlTicks() + 100).isEmpty());
    }

    @Test
    void thePersonBoundViewSpeaksForItsPerson() {
        AgentClaims theirs = claims.forPerson(alice);
        AgentClaims his = claims.forPerson(bob);
        assertTrue(theirs.claim(TestPois.TREE, anchor, 0));
        assertFalse(his.claim(TestPois.TREE, anchor, 1));
        assertFalse(his.availableTo(TestPois.TREE, anchor, 1));
        theirs.release(TestPois.TREE, anchor);
        assertTrue(his.availableTo(TestPois.TREE, anchor, 2));
    }
}
