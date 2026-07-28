package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure knowledge store: note/refresh/forget rules, grove-merge
 * semantics, staleness-based eviction. No Minecraft — the whole point of the {@code core} layer.
 */
class AgentKnowledgeTest {

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
        knowledge.note(tree(10, 64, 0, 6, 100));
        knowledge.note(tree(40, 64, 0, 6, 100));
        knowledge.note(water(12, 63, 0, 100));

        PoiMemory nearest = knowledge.nearest(TestPois.TREE, new Pos(0, 64, 0)).orElseThrow();
        assertEquals(new Pos(10, 64, 0), nearest.anchor(), "closer tree wins; water ignored");
        assertTrue(knowledge.nearest(TestPois.TREE, new Pos(100, 64, 0)).isPresent());
    }

    @Test
    void nearestOnAnEmptyKindIsEmpty() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(water(5, 63, 5, 1));
        assertTrue(knowledge.nearest(TestPois.TREE, new Pos(0, 64, 0)).isEmpty());
    }

    @Test
    void refreshBumpsLastSeenAndMissesAreNotErrors() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(10, 64, 0, 6, 100));

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
        knowledge.note(tree(10, 64, 0, 6, 100));
        knowledge.note(tree(40, 64, 0, 4, 100));

        assertTrue(knowledge.forget(TestPois.TREE, new Pos(10, 64, 0)));
        assertFalse(knowledge.forget(TestPois.TREE, new Pos(10, 64, 0)), "already gone");
        assertEquals(1, knowledge.size());
        assertEquals(new Pos(40, 64, 0),
                knowledge.nearest(TestPois.TREE, new Pos(0, 64, 0)).orElseThrow().anchor());
    }

    @Test
    void notingWithinMergeRadiusReplacesInsteadOfDuplicating() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(10, 64, 10, 23, 100));
        // The same 2x2-trunk grove, re-discovered from its other corner after some chopping.
        PoiMemory rediscovered = tree(11, 64, 11, 17, 900);
        knowledge.note(rediscovered);

        assertEquals(1, knowledge.size(), "one grove, one memory");
        PoiMemory stored = knowledge.all(TestPois.TREE).iterator().next();
        assertEquals(rediscovered, stored, "the fresher expansion wins outright");
    }

    @Test
    void mergeNeverCrossesKindsOrItsRadius() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(10, 64, 10, 6, 100));
        knowledge.note(water(11, 64, 11, 100));
        knowledge.note(tree(12, 64, 10, 4, 100));

        assertEquals(3, knowledge.size(),
                "water beside a tree is not the tree; and two trunks two apart are two trees — "
                        + "TREE's radius merges only what the shape would call one trunk");
    }

    @Test
    void waterMergesAcrossItsWiderRadius() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(water(0, 63, 0, 100));
        knowledge.note(water(7, 63, 5, 200));

        assertEquals(1, knowledge.size(), "same lake met from two spots along the shore");
    }

    @Test
    void capacityEvictsTheStalestOfThatKind() {
        AgentKnowledge knowledge = new AgentKnowledge();
        // Fill to cap with well-separated groves; entry i was last seen at tick i+1, except
        // entry 20 which is the stalest of all.
        for (int i = 0; i < AgentKnowledge.maxPerKind(); i++) {
            long seen = (i == 20) ? 0 : i + 1;
            knowledge.note(tree(i * 10, 64, 0, 5, seen));
        }
        assertEquals(AgentKnowledge.maxPerKind(), knowledge.size());

        knowledge.note(tree(0, 64, 500, 5, 5000));

        assertEquals(AgentKnowledge.maxPerKind(), knowledge.size(), "capacity holds");
        assertFalse(knowledge.forget(TestPois.TREE, new Pos(200, 64, 0)),
                "the stalest entry (i=20, seen at tick 0) was the one evicted");
        assertTrue(knowledge.forget(TestPois.TREE, new Pos(0, 64, 500)), "newcomer is in");
    }

    @Test
    void allIsUnmodifiableAndInsertionOrdered() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(tree(40, 64, 0, 4, 100));
        knowledge.note(tree(10, 64, 0, 6, 200));

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
}
