package dev.luizloyola.anima.core.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** One bed a body, one body a bed. */
class BedClaimsTest {

    private static final Pos BED = new Pos(0, 64, 0);
    private static final Pos OTHER = new Pos(3, 64, 0);

    private final BedClaims claims = new BedClaims();
    private final AgentId ada = AgentId.random();
    private final AgentId bram = AgentId.random();

    @Test
    void aBedHeldByOneIsRefusedToAnother() {
        assertTrue(claims.claim(ada, BED));
        assertFalse(claims.claim(bram, BED));
        assertEquals(Optional.of(ada), claims.sleeperIn(BED));
        assertTrue(claims.claim(ada, BED), "claiming one's own bed again is no refusal");
    }

    @Test
    void aSecondBedLetsGoOfTheFirst() {
        claims.claim(ada, BED);
        claims.claim(ada, OTHER);

        assertEquals(Optional.of(OTHER), claims.bedOf(ada));
        assertTrue(claims.claim(bram, BED));
    }

    @Test
    void releasedTheBedIsFree() {
        int[] changes = {0};
        claims.onChange(() -> changes[0]++);
        claims.claim(ada, BED);
        claims.release(ada);

        assertTrue(claims.sleeperIn(BED).isEmpty());
        assertTrue(claims.bedOf(ada).isEmpty());
        assertEquals(2, changes[0]);
    }
}
