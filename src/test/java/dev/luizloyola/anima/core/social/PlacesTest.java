package dev.luizloyola.anima.core.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a claimed place is: owned, shared or communal, visible to its owner and to its party, and
 * never to anybody else. A sighting is not a claim and does not live here.
 */
class PlacesTest {

    private static final PoiKind BENCH = PoiKind.register("test_bench", 1, "");

    private final AgentId hazel = AgentId.random();
    private final AgentId rowan = AgentId.random();
    private final PartyId together = PartyId.random();

    /** A roster stand-in: whatever the test says, minting on demand like the real one. */
    private final Map<AgentId, PartyId> membership = new java.util.HashMap<>();

    private final Places places = new Places();

    @org.junit.jupiter.api.BeforeEach
    void wireTheRoster() {
        places.asks(new Places.Parties() {
            @Override
            public Optional<PartyId> current(AgentId who) {
                return Optional.ofNullable(membership.get(who));
            }

            @Override
            public PartyId of(AgentId who) {
                return membership.computeIfAbsent(who, k -> PartyId.random());
            }
        });
    }

    @Test
    void aRowWithNeitherOwnerNorPartyIsNotAClaim() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaceRow(BENCH, new Pos(1, 2, 3), null, null, 0L));
    }

    @Test
    void anOwnerSeesTheirOwnPlaceAndNobodyElseDoes() {
        places.found(BENCH, new Pos(1, 64, 1), hazel, null, 10L);
        assertTrue(places.viewFor(hazel).nearest(BENCH, new Pos(0, 64, 0)).isPresent());
        assertTrue(places.viewFor(rowan).nearest(BENCH, new Pos(0, 64, 0)).isEmpty(),
                "a personal claim is not the party's business");
    }

    @Test
    void aCommunalPlaceIsVisibleToEveryMemberAndNoOutsider() {
        membership.put(hazel, together);
        membership.put(rowan, together);
        AgentId stranger = AgentId.random();
        places.found(BENCH, new Pos(8, 64, 8), null, together, 10L);

        assertTrue(places.viewFor(hazel).nearest(BENCH, new Pos(0, 0, 0)).isPresent());
        assertTrue(places.viewFor(rowan).nearest(BENCH, new Pos(0, 0, 0)).isPresent(),
                "membership is the whole of the claim");
        assertTrue(places.viewFor(stranger).nearest(BENCH, new Pos(0, 0, 0)).isEmpty());
    }

    @Test
    void foundingCommunalMintsThePartyWhenTheFounderHasNone() {
        places.viewFor(hazel).foundCommunal(BENCH, new Pos(4, 64, 4), 20L);
        assertTrue(membership.containsKey(hazel), "founding needs a party to found into");
        assertTrue(places.viewFor(hazel).nearest(BENCH, new Pos(0, 0, 0)).isPresent());
    }

    @Test
    void aReadNeverMintsAParty() {
        places.viewFor(hazel).nearest(BENCH, new Pos(0, 0, 0));
        places.viewFor(hazel).all(BENCH);
        assertTrue(membership.isEmpty(), "reading must not create a party as a side effect");
    }

    @Test
    void droppingRemovesTheRowForEveryone() {
        membership.put(hazel, together);
        membership.put(rowan, together);
        places.found(BENCH, new Pos(2, 64, 2), null, together, 10L);
        assertTrue(places.viewFor(rowan).drop(BENCH, new Pos(2, 64, 2)));
        assertTrue(places.viewFor(hazel).nearest(BENCH, new Pos(0, 0, 0)).isEmpty());
        assertFalse(places.viewFor(rowan).drop(BENCH, new Pos(2, 64, 2)), "already gone");
    }

    @Test
    void nearestIsNearest() {
        membership.put(hazel, together);
        places.found(BENCH, new Pos(20, 64, 0), null, together, 1L);
        places.found(BENCH, new Pos(3, 64, 0), null, together, 1L);
        assertEquals(new Pos(3, 64, 0),
                places.viewFor(hazel).nearest(BENCH, new Pos(0, 0, 0)).orElseThrow().at());
    }

    @Test
    void aClaimRendersAsAMemoryThatIsNeverStale() {
        membership.put(hazel, together);
        places.found(BENCH, new Pos(5, 64, 5), null, together, 1L);
        assertEquals(900L, places.viewFor(hazel).nearest(BENCH, new Pos(0, 0, 0))
                .orElseThrow().toMemory(900L).lastSeenTick(),
                "a claim is a fact, not a sighting that ages");
    }

    @Test
    void theEmptyViewAnswersNothingAndAcceptsNothing() {
        assertTrue(Places.View.EMPTY.nearest(BENCH, new Pos(0, 0, 0)).isEmpty());
        assertTrue(Places.View.EMPTY.all(BENCH).isEmpty());
        assertFalse(Places.View.EMPTY.drop(BENCH, new Pos(0, 0, 0)));
    }
}
