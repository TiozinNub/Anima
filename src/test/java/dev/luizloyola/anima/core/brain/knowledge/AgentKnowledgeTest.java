package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.Places;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure knowledge store: note/refresh/forget rules, grove-merge
 * semantics, staleness-based eviction. No Minecraft — the whole point of the {@code core} layer.
 */
class AgentKnowledgeTest {

    /** Stands in for a claimed, perceivable block — the same shape as {@code PlacesTest}'s. */
    private static final PoiKind BENCH = PoiKind.register("test_bench", 1, "");

    private static PoiMemory tree(int x, int y, int z, int logs, long seen) {
        Pos anchor = new Pos(x, y, z);
        return new PoiMemory(TestPois.TREE, anchor, Region.of(anchor), logs, false, seen);
    }

    private static PoiMemory water(int x, int y, int z, long seen) {
        Pos anchor = new Pos(x, y, z);
        return new PoiMemory(TestPois.WATER, anchor, Region.of(anchor), 1, false, seen);
    }

    @Test
    void nearestPicksTheClosestAnchorOfTheAskedKind() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(10, 64, 0, 6, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        knowledge.note(tree(40, 64, 0, 6, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        knowledge.note(water(12, 63, 0, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        PoiMemory nearest = knowledge.nearest(TestPois.TREE, new Pos(0, 64, 0)).orElseThrow();
        assertEquals(new Pos(10, 64, 0), nearest.anchor(), "closer tree wins; water ignored");
        assertTrue(knowledge.nearest(TestPois.TREE, new Pos(100, 64, 0)).isPresent());
    }

    @Test
    void nearestOnAnEmptyKindIsEmpty() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(water(5, 63, 5, 1), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        assertTrue(knowledge.nearest(TestPois.TREE, new Pos(0, 64, 0)).isEmpty());
    }

    @Test
    void refreshBumpsLastSeenAndMissesAreNotErrors() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(10, 64, 0, 6, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        assertTrue(knowledge.refresh(TestPois.TREE, new Pos(10, 64, 0), 500));
        PoiMemory refreshed = knowledge.all(TestPois.TREE).iterator().next();
        assertEquals(500, refreshed.lastSeenTick());
        assertEquals(0, refreshed.age(500));

        assertFalse(knowledge.refresh(TestPois.TREE, new Pos(99, 64, 0), 500), "unknown anchor");
        assertFalse(knowledge.refresh(TestPois.WATER, new Pos(10, 64, 0), 500), "wrong kind");
    }

    @Test
    void forgetDropsExactlyTheAskedEntry() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(10, 64, 0, 6, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        knowledge.note(tree(40, 64, 0, 4, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        assertTrue(knowledge.forget(TestPois.TREE, new Pos(10, 64, 0)));
        assertFalse(knowledge.forget(TestPois.TREE, new Pos(10, 64, 0)), "already gone");
        assertEquals(1, knowledge.size());
        assertEquals(new Pos(40, 64, 0),
                knowledge.nearest(TestPois.TREE, new Pos(0, 64, 0)).orElseThrow().anchor());
    }

    @Test
    void notingWithinMergeRadiusReplacesInsteadOfDuplicating() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(10, 64, 10, 23, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        // The same 2x2-trunk grove, re-discovered from its other corner after some chopping.
        PoiMemory rediscovered = tree(11, 64, 11, 17, 900);
        knowledge.note(rediscovered, AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        assertEquals(1, knowledge.size(), "one grove, one memory");
        PoiMemory stored = knowledge.all(TestPois.TREE).iterator().next();
        assertEquals(rediscovered, stored, "the fresher expansion wins outright");
    }

    @Test
    void mergeNeverCrossesKindsOrItsRadius() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(10, 64, 10, 6, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        knowledge.note(water(11, 64, 11, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        knowledge.note(tree(12, 64, 10, 4, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        assertEquals(3, knowledge.size(),
                "water beside a tree is not the tree; and two trunks two apart are two trees — "
                        + "TREE's radius merges only what the shape would call one trunk");
    }

    @Test
    void waterMergesAcrossItsWiderRadius() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(water(0, 63, 0, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        knowledge.note(water(7, 63, 5, 200), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        assertEquals(1, knowledge.size(), "same lake met from two spots along the shore");
    }

    @Test
    void capacityEvictsTheStalestOfThatKind() {
        AgentKnowledge knowledge = new AgentKnowledge();
        // Fill to cap with well-separated groves; entry i was last seen at tick i+1, except
        // entry 20 which is the stalest of all.
        for (int i = 0; i < AgentKnowledge.maxPerKind(TestSpecies.PROFILE); i++) {
            long seen = (i == 20) ? 0 : i + 1;
            knowledge.note(tree(i * 10, 64, 0, 5, seen), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        }
        assertEquals(AgentKnowledge.maxPerKind(TestSpecies.PROFILE), knowledge.size());

        knowledge.note(tree(0, 64, 500, 5, 5000), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        assertEquals(AgentKnowledge.maxPerKind(TestSpecies.PROFILE), knowledge.size(), "capacity holds");
        assertFalse(knowledge.forget(TestPois.TREE, new Pos(200, 64, 0)),
                "the stalest entry (i=20, seen at tick 0) was the one evicted");
        assertTrue(knowledge.forget(TestPois.TREE, new Pos(0, 64, 500)), "newcomer is in");
    }

    @Test
    void allIsUnmodifiableAndInsertionOrdered() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(40, 64, 0, 4, 100), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        knowledge.note(tree(10, 64, 0, 6, 200), AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        List<PoiMemory> all = List.copyOf(knowledge.all(TestPois.TREE));
        assertEquals(new Pos(40, 64, 0), all.get(0).anchor(), "insertion order, not distance");
        assertThrows(UnsupportedOperationException.class,
                () -> knowledge.all(TestPois.TREE).clear());
    }

    @Test
    void memoryValidatesItsOwnShape() {
        Pos anchor = new Pos(0, 64, 0);
        Region bounds = new Region(new Pos(0, 64, 0), new Pos(4, 70, 4));
        assertTrue(bounds.contains(anchor));
        assertThrows(IllegalArgumentException.class, () ->
                new PoiMemory(TestPois.TREE, new Pos(9, 64, 0), bounds, 5, false, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new PoiMemory(TestPois.TREE, anchor, bounds, -1, false, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new Region(new Pos(1, 0, 0), new Pos(0, 5, 5)));
    }

    @Test
    void regionIncludingGrowsExactlyAsNeeded() {
        Region region = Region.of(new Pos(5, 64, 5));
        Region grown = region.including(new Pos(3, 70, 8));
        assertEquals(new Region(new Pos(3, 64, 5), new Pos(5, 70, 8)), grown);
        assertEquals(grown, grown.including(new Pos(4, 65, 6)), "inside -> same box (same object)");
    }

    @Test
    void aClaimIsFoundByAnAgentWhoNeverSawIt() {
        AgentId hazel = AgentId.random();
        Places places = new Places();
        places.asks(everyoneAlone());
        places.found(BENCH, new Pos(20, 64, 0), hazel, null, 1L);

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(hazel));

        assertEquals(new Pos(20, 64, 0),
                knowledge.nearest(BENCH, new Pos(0, 0, 0)).orElseThrow().anchor(),
                "nothing was ever noted; the claim is the whole of what is known");
    }

    @Test
    void aBlockBothSeenAndClaimedIsOneEntry() {
        AgentId hazel = AgentId.random();
        Pos at = new Pos(4, 64, 4);
        Places places = new Places();
        places.asks(everyoneAlone());
        places.found(BENCH, at, hazel, null, 1L);

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(hazel));
        knowledge.note(new PoiMemory(BENCH, at, Region.of(at), 0, false, 50L), 64);

        assertEquals(1, knowledge.all(BENCH).size(),
                "a workbench is perceived as well as claimed; it is still one workbench");
    }

    @Test
    void disprovingCorrectsTheClaimAsWellAsTheSighting() {
        AgentId hazel = AgentId.random();
        Pos at = new Pos(6, 64, 6);
        Places places = new Places();
        places.asks(everyoneAlone());
        places.found(BENCH, at, hazel, null, 1L);

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(hazel));

        assertTrue(knowledge.disprove(BENCH, at), "the probe found nothing standing there");
        assertTrue(knowledge.nearest(BENCH, new Pos(0, 0, 0)).isEmpty());
        assertTrue(places.rows().isEmpty(), "the correction is the party's, not just this body's");
    }

    @Test
    void forgetNeverTouchesAClaim() {
        // DangerNoter and HerdNoter re-key with forget-then-note ("moved, not duplicated"); if
        // forget reached a claim, a re-key on a claimed anchor would silently erase somebody's
        // workshop with nothing to re-found it.
        AgentId hazel = AgentId.random();
        Pos at = new Pos(7, 64, 7);
        Places places = new Places();
        places.asks(everyoneAlone());
        places.found(BENCH, at, hazel, null, 1L);

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(hazel));

        assertFalse(knowledge.forget(BENCH, at), "no sighting there; forget must not see the claim");
        assertTrue(knowledge.nearest(BENCH, new Pos(0, 0, 0)).isPresent(), "the claim survives");
        assertFalse(places.rows().isEmpty(), "forget is not the probe correction");
    }

    @Test
    void sightedNeverIncludesAClaim() {
        AgentId hazel = AgentId.random();
        Pos at = new Pos(11, 64, 11);
        Places places = new Places();
        places.asks(everyoneAlone());
        places.found(BENCH, at, hazel, null, 1L);

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(hazel));

        assertTrue(knowledge.sighted(BENCH).isEmpty(),
                "sighted is what this store persists; a claim is Places's row, not this body's");
        assertEquals(1, knowledge.all(BENCH).size(), "but the composed read still finds it");
    }

    @Test
    void aClaimsMemoryIsStampedWithTheCurrentTickNotWhenItWasFounded() {
        AgentId hazel = AgentId.random();
        Pos at = new Pos(13, 64, 13);
        Places places = new Places();
        places.asks(everyoneAlone());
        places.found(BENCH, at, hazel, null, 5L); // founded long ago

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(hazel), () -> 9_000L); // but the clock reads now

        assertEquals(9_000L, knowledge.nearest(BENCH, new Pos(0, 0, 0)).orElseThrow().lastSeenTick(),
                "a claim must never look stale just because it was made long ago");
        assertEquals(9_000L, knowledge.all(BENCH).iterator().next().lastSeenTick());
    }

    @Test
    void aPartyMemberSeesAWorkshopFoundedByAnotherMember() {
        // Every earlier claim test founds owner=hazel and reads as hazel, so all of them resolve
        // through `who.equals(owner)` and never touch the party branch of PlaceRow.visibleTo. A
        // communal row read by a DIFFERENT member is the one path that actually exercises it.
        AgentId hazel = AgentId.random();
        AgentId rowan = AgentId.random();
        PartyId together = PartyId.random();
        Pos at = new Pos(9, 64, 9);
        Places places = new Places();
        places.asks(partyOf(together, hazel, rowan));
        places.found(BENCH, at, null, together, 1L); // communal: nobody's personal sighting

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(rowan)); // rowan never saw it; the claim is what tells them

        assertEquals(at, knowledge.nearest(BENCH, new Pos(0, 0, 0)).orElseThrow().anchor(),
                "the workshop is the party's; a member who never walked past it still knows it "
                        + "stands");
    }

    @Test
    void disproveRefusesAClaimItCannotSee() {
        AgentId hazel = AgentId.random();
        AgentId stranger = AgentId.random();
        Pos at = new Pos(3, 64, 3);
        Places places = new Places();
        places.asks(everyoneAlone()); // hazel and stranger are each their own party of one
        places.found(BENCH, at, hazel, null, 1L);

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(stranger));

        assertFalse(knowledge.disprove(BENCH, at),
                "a stranger's probe cannot correct a claim they are not party to");
        assertFalse(places.rows().isEmpty(), "the claim survives an outsider's negative report");
    }

    @Test
    void notingASightingNeverMakesAClaim() {
        AgentId hazel = AgentId.random();
        Pos at = new Pos(8, 64, 8);
        Places places = new Places();
        places.asks(everyoneAlone());

        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.sees(places.viewFor(hazel));
        knowledge.note(new PoiMemory(BENCH, at, Region.of(at), 0, false, 50L), 64);

        assertTrue(places.rows().isEmpty(), "walking past a thing does not claim it");
    }

    @Test
    void aKnowledgeWithNoViewBehavesExactlyAsBefore() {
        AgentKnowledge knowledge = new AgentKnowledge();
        assertTrue(knowledge.all(BENCH).isEmpty());
        assertTrue(knowledge.sighted(BENCH).isEmpty());
        assertTrue(knowledge.nearest(BENCH, new Pos(0, 0, 0)).isEmpty());
        assertFalse(knowledge.forget(BENCH, new Pos(0, 0, 0)));
        assertFalse(knowledge.disprove(BENCH, new Pos(0, 0, 0)));
    }

    private static Places.Parties everyoneAlone() {
        return new Places.Parties() {
            @Override
            public java.util.Optional<PartyId> current(AgentId who) {
                return java.util.Optional.empty();
            }

            @Override
            public PartyId of(AgentId who) {
                return PartyId.random();
            }
        };
    }

    /** Membership fixed at construction — {@code who} in the party sees it; nobody else does. */
    private static Places.Parties partyOf(PartyId party, AgentId... members) {
        java.util.Set<AgentId> roster = java.util.Set.of(members);
        return new Places.Parties() {
            @Override
            public java.util.Optional<PartyId> current(AgentId who) {
                return roster.contains(who) ? java.util.Optional.of(party) : java.util.Optional.empty();
            }

            @Override
            public PartyId of(AgentId who) {
                return party;
            }
        };
    }
}
