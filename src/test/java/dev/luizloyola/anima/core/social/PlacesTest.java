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

    @Test
    void aDisbandedPartysWorkshopFollowsItsLastMember() {
        PartyId alone = PartyId.random();
        membership.put(hazel, alone);
        places.found(BENCH, new Pos(88, 64, -12), null, alone, 5L);

        membership.put(hazel, together);
        assertEquals(1, places.partyDisbanded(alone, together));

        membership.put(rowan, together);
        assertTrue(places.viewFor(rowan).nearest(BENCH, new Pos(0, 0, 0)).isPresent(),
                "Rowan has never seen it; membership is how they know");
    }

    @Test
    void aCommunalRowWithNowhereToGoIsDropped() {
        PartyId alone = PartyId.random();
        places.found(BENCH, new Pos(1, 64, 1), null, alone, 5L);
        assertEquals(1, places.partyDisbanded(alone, null));
        assertTrue(places.rows().isEmpty(), "nothing owns it, so nothing keeps it");
    }

    @Test
    void anOwnedRowSurvivesItsPartyAndRevertsToPersonal() {
        PartyId alone = PartyId.random();
        places.found(BENCH, new Pos(2, 64, 2), hazel, alone, 5L);
        assertEquals(1, places.ownerMovedTo(hazel, null));

        PlaceRow row = places.rows().iterator().next();
        assertEquals(hazel, row.owner(), "a dead settler's chest is still theirs");
        assertEquals(null, row.party());
        membership.put(hazel, together);
        assertTrue(places.viewFor(hazel).nearest(BENCH, new Pos(0, 0, 0)).isPresent());
        membership.put(rowan, together);
        assertTrue(places.viewFor(rowan).nearest(BENCH, new Pos(0, 0, 0)).isEmpty(),
                "leaving unshared it");
    }

    @Test
    void anOwnedRowFollowsItsOwnerIntoTheNewParty() {
        places.found(BENCH, new Pos(3, 64, 3), hazel, null, 5L);
        assertEquals(1, places.ownerMovedTo(hazel, together));
        membership.put(rowan, together);
        assertTrue(places.viewFor(rowan).nearest(BENCH, new Pos(0, 0, 0)).isPresent());
    }

    @Test
    void aTransitionTouchesNobodyElsesRows() {
        PartyId alone = PartyId.random();
        places.found(BENCH, new Pos(4, 64, 4), rowan, together, 5L);
        assertEquals(0, places.partyDisbanded(alone, together));
        assertEquals(0, places.ownerMovedTo(hazel, null));
        assertEquals(together, places.rows().iterator().next().party());
    }

    @Test
    void aDisbandingPartyDoesNotTakeAnotherPartysCommunalRow() {
        // aTransitionTouchesNobodyElsesRows' row is owned, so it is skipped by partyDisbanded's
        // OWNER guard before the party comparison below ever runs — the "wrong party is untouched"
        // half of that guard has nothing pinning it. A communal row does.
        PartyId alone = PartyId.random();
        PartyId elsewhere = PartyId.random();
        places.found(BENCH, new Pos(5, 64, 5), null, elsewhere, 5L);
        assertEquals(0, places.partyDisbanded(alone, together));
        assertEquals(elsewhere, places.rows().iterator().next().party(), "untouched");
    }

    @Test
    void aDisbandingPartyDoesNotTakeSomebodyElsesOwnedRow() {
        places.found(BENCH, new Pos(9, 64, 9), hazel, together, 5L);
        assertEquals(0, places.partyDisbanded(together, PartyId.random()),
                "an owned row's sharing follows its owner, not the party it was shared with");
        PlaceRow row = places.rows().iterator().next();
        assertEquals(hazel, row.owner());
        assertEquals(together, row.party(), "untouched");
    }
}
