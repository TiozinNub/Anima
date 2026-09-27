package dev.luizloyola.anima.core.brain.act;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.act.Sweep.Kind;
import dev.luizloyola.anima.core.social.PartyId;
import org.junit.jupiter.api.Test;

/** The combat spec's sweep table (decision 14), row by row. {@code null} is party 0. */
class SweepTest {

    private static final PartyId ONE = PartyId.random();
    private static final PartyId TWO = PartyId.random();

    @Test
    void aBlowAtAMobCatchesNoAgentAndNoPlayer() {
        assertEquals(false, Sweep.catches(Kind.OTHER, null, Kind.AGENT, ONE));
        assertEquals(false, Sweep.catches(Kind.OTHER, null, Kind.PLAYER, ONE));
        assertEquals(false, Sweep.catches(Kind.OTHER, null, Kind.PLAYER, null));
    }

    @Test
    void mobsBesideAnyTargetAreSweptAsVanillaSweepsThem() {
        assertEquals(true, Sweep.catches(Kind.OTHER, null, Kind.OTHER, null));
        assertEquals(true, Sweep.catches(Kind.AGENT, ONE, Kind.OTHER, null));
        assertEquals(true, Sweep.catches(Kind.PLAYER, null, Kind.OTHER, null));
    }

    @Test
    void theSpecsTable() {
        assertEquals(false, Sweep.catches(Kind.PLAYER, ONE, Kind.PLAYER, TWO), "player 1, player 2");
        assertEquals(true, Sweep.catches(Kind.PLAYER, ONE, Kind.PLAYER, null), "player 1, player 0");
        assertEquals(true, Sweep.catches(Kind.PLAYER, null, Kind.PLAYER, null), "player 0, player 0");
        assertEquals(false, Sweep.catches(Kind.PLAYER, null, Kind.PLAYER, ONE), "player 0, player 1");
        assertEquals(true, Sweep.catches(Kind.AGENT, ONE, Kind.PLAYER, ONE), "agent 1, player 1");
        assertEquals(false, Sweep.catches(Kind.AGENT, ONE, Kind.PLAYER, null), "agent 1, player 0");
        assertEquals(false, Sweep.catches(Kind.AGENT, ONE, Kind.AGENT, TWO), "agent 1, agent 2");
    }

    @Test
    void agentsAndPlayersAreCheckedTheSameWay() {
        assertEquals(true, Sweep.catches(Kind.AGENT, ONE, Kind.AGENT, ONE));
        assertEquals(true, Sweep.catches(Kind.PLAYER, ONE, Kind.AGENT, ONE));
        assertEquals(false, Sweep.catches(Kind.PLAYER, ONE, Kind.AGENT, TWO));
    }
}
