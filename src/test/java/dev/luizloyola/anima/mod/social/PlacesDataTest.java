package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A claim writes itself down and comes back as it was, through {@code JsonOps} — no server, no save
 * file. Vanilla parses saved data with {@code resultOrPartial}: a row that stopped decoding does not
 * fail the load, it comes back with fewer rows and the next autosave writes that over the file.
 */
class PlacesDataTest {

    private static final PoiKind BENCH = PoiKind.register("test_store_bench", 1, "");

    @Test
    void everyOwnershipStateSurvivesTheRoundTrip() {
        AgentId hazel = AgentId.random();
        PartyId party = PartyId.random();
        for (PlaceRow before : java.util.List.of(
                new PlaceRow(BENCH, new Pos(1, 64, 2), hazel, null, 5L),
                new PlaceRow(BENCH, new Pos(3, 64, 4), hazel, party, 6L),
                new PlaceRow(BENCH, new Pos(5, 64, 6), null, party, 7L))) {
            var encoded = PlacesData.ROW_CODEC.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
            PlaceRow after = PlacesData.ROW_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            assertEquals(before, after, "the ownership state is the whole meaning of the row");
        }
    }

    @Test
    void theStoreCountsWhatItHoldsForTheBootGuard() {
        PlacesData store = new PlacesData();
        assertEquals(0, store.actualRows());
        store.places().found(BENCH, new Pos(1, 64, 1), AgentId.random(), null, 1L);
        assertEquals(1, store.actualRows(), "the guard compares this against the file's own count");
    }

    @Test
    void aFreshStoreSaysItWasNeverLoaded() {
        assertEquals(dev.luizloyola.anima.mod.store.StoreGuard.NEVER_LOADED,
                new PlacesData().loadedVersion(),
                "that is the whole signal StoreGuard reads to catch a swallowed parse failure");
    }

    @Test
    void anUnwiredStoreShowsNothingRatherThanThrowing() {
        PlacesData store = new PlacesData();
        AgentId hazel = AgentId.random();
        store.places().found(BENCH, new Pos(1, 64, 1), hazel, null, 1L);
        assertTrue(store.places().viewFor(hazel).nearest(BENCH, new Pos(0, 0, 0)).isPresent(),
                "an owner needs no roster to see their own claim");
        assertTrue(store.places().viewFor(AgentId.random()).nearest(BENCH, new Pos(0, 0, 0)).isEmpty());
    }

    @Test
    void joiningCarriesALonersWorkshopIntoTheNewParty() {
        // PartyData has a public no-arg constructor and its mutators are pure logic over the
        // roster, so the whole transition is testable with no server — the GravesTest precedent.
        PartyData parties = new PartyData();
        Places places = new Places();
        parties.follows(places);

        AgentId fen = AgentId.random();
        AgentId hazel = AgentId.random();
        PartyId alone = parties.partyOf(fen);
        PartyId theirs = parties.partyOf(hazel);
        places.found(BENCH, new Pos(88, 64, -12), null, alone, 5L);

        assertTrue(parties.join(fen, theirs));
        assertEquals(theirs, places.rows().iterator().next().party(),
                "Fen's party of one ceased to exist, so its table went where Fen went");
    }

    @Test
    void leavingDoesNotStripAPartyOfItsOwnWorkshop() {
        PartyData parties = new PartyData();
        Places places = new Places();
        parties.follows(places);

        AgentId fen = AgentId.random();
        AgentId hazel = AgentId.random();
        PartyId theirs = parties.partyOf(hazel);
        parties.join(fen, theirs);
        places.found(BENCH, new Pos(1, 64, 1), null, theirs, 5L);

        assertTrue(parties.leave(fen));
        assertEquals(theirs, places.rows().iterator().next().party(),
                "the party still exists and still knows its own table");
    }

    @Test
    void theRosterLookupNeverMintsOnARead() {
        Places places = new Places();
        java.util.List<AgentId> minted = new java.util.ArrayList<>();
        places.asks(new Places.Parties() {
            @Override
            public Optional<PartyId> current(AgentId who) {
                return Optional.empty();
            }

            @Override
            public PartyId of(AgentId who) {
                minted.add(who);
                return PartyId.random();
            }
        });
        places.viewFor(AgentId.random()).nearest(BENCH, new Pos(0, 0, 0));
        assertTrue(minted.isEmpty(), "partyOf mints; a read must go through currentPartyOf");
    }

    // --- Ruling A: forgetOwner fires the listener itself; PlacesData.forget must not need its own
    // dirty-marking convention on top of it. These assert through the listener, never through
    // SavedData internals. ---

    @Test
    void anErasureThatDroppedAClaimMarksTheStoreDirty() {
        PlacesData store = new PlacesData();
        AgentId hazel = AgentId.random();
        store.places().found(BENCH, new Pos(1, 64, 1), hazel, null, 1L);

        AtomicBoolean dirtied = new AtomicBoolean();
        store.places().onChange(() -> dirtied.set(true));

        assertTrue(store.forget(hazel), "hazel owned a claim, so there was something to erase");
        assertTrue(dirtied.get(),
                "forget must mark the store dirty through the same listener every other mutator "
                        + "uses, not a second convention of its own");
    }

    @Test
    void forgettingAnOwnerWithNoClaimsDoesNotFalselyMarkDirty() {
        PlacesData store = new PlacesData();
        AtomicBoolean dirtied = new AtomicBoolean();
        store.places().onChange(() -> dirtied.set(true));

        assertFalse(store.forget(AgentId.random()), "nobody owned anything here");
        assertFalse(dirtied.get(), "nothing changed, so nothing should mark the store dirty");
    }
}
