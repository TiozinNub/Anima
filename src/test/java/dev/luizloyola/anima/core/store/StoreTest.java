package dev.luizloyola.anima.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule.Evaluation;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A store is a place like a workbench is a place: one cell, one store — a row of chests is a row of
 * stores and not a warehouse.
 */
class StoreTest {

    @Test
    void aStoreIsItsOwnKindAndItsOwnBlock() {
        assertNotNull(Store.BLOCK);
        assertNotNull(Store.POI);
        assertEquals("store", Store.POI.key());
    }

    @Test
    void twoAdjacentChestsAreTwoStores() {
        assertEquals(0, Store.POI.mergeRadius(),
                "merging neighbours would make a wall of chests one place and lose the one you meant");
    }

    @Test
    void whatItPlacesIsAPlainChest() {
        assertEquals("minecraft:chest", Store.ITEM_ID);
    }

    @Test
    void reachIsTheSurvivalPlayersOwnArm() {
        assertTrue(Store.REACH > 0 && Store.REACH <= 5.0);
    }

    private static final Pos CHEST = new Pos(4, 64, 4);

    /** A body standing beside {@code CHEST} and remembering a store there. */
    private static FakeContext ctxBeside() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(CHEST.x() + 1, CHEST.y(), CHEST.z());
        ctx.knowledge.note(new PoiMemory(Store.POI, CHEST, Region.of(CHEST), 1, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));
        return ctx;
    }

    @Test
    void standingAtOneReReadsTheRememberedCellRatherThanTrustingIt() {
        FakeContext ctx = ctxBeside();
        ctx.percepts.blocks.set(CHEST.x(), CHEST.y(), CHEST.z(), Store.BLOCK);
        assertTrue(Store.standingAtOne(ctx));

        ctx.percepts.blocks.clear(CHEST.x(), CHEST.y(), CHEST.z());
        assertFalse(Store.standingAtOne(ctx), "the chest was mined out from under the memory");
        assertTrue(ctx.knowledge.all(Store.POI).isEmpty(),
                "a claim the world no longer backs is dropped, not left to send bodies back");
    }

    @Test
    void aRememberedStoreOutOfArmsReachIsNotOneToStandAt() {
        FakeContext ctx = ctxBeside();
        ctx.percepts.blocks.set(CHEST.x(), CHEST.y(), CHEST.z(), Store.BLOCK);
        ctx.percepts.position = new Pos(CHEST.x() + 20, CHEST.y(), CHEST.z());

        assertFalse(Store.standingAtOne(ctx));
        assertFalse(ctx.knowledge.all(Store.POI).isEmpty(),
                "too far to touch is no evidence at all — the memory must survive the walk away");
    }

    @Test
    void nearestKnownNamesTheClosestRememberedStore() {
        FakeContext ctx = ctxBeside();
        assertEquals(CHEST, Store.nearestKnown(ctx).orElseThrow().anchor());
        assertTrue(Store.nearestKnown(new FakeContext()).isEmpty(), "nothing remembered, nothing named");
    }

    // --- a double chest: two block entities, one chest ----------------------------------------

    private static final Pos ANCHOR = new Pos(0, 64, 0);
    private static final BlockProbe UNREAD = new FakeProbe();

    /** The cells each returned evaluation claims, as a set per thing, for order-free comparison. */
    private static Set<Set<Pos>> thingsIn(Map<Pos, BlockKind> mass) {
        Set<Set<Pos>> things = new HashSet<>();
        for (Evaluation found : Store.RULE.evaluate(mass, UNREAD)) {
            things.add(found.blocks().keySet());
        }
        return things;
    }

    private static Map<Pos, BlockKind> mass(Pos a, BlockKind ka, Pos b, BlockKind kb) {
        Map<Pos, BlockKind> cells = new LinkedHashMap<>();
        cells.put(a, ka);
        cells.put(b, kb);
        return cells;
    }

    @Test
    void theFarHalfOfADoubleStoreIsStillSomewhereToPutThings() {
        assertTrue(Store.isStore(Store.BLOCK));
        assertTrue(Store.isStore(Store.FAR_X));
        assertTrue(Store.isStore(Store.FAR_Z));
        assertFalse(Store.isStore(BlockKind.OTHER));
        assertEquals(List.of(Store.BLOCK, Store.FAR_X, Store.FAR_Z), Store.SEEDS,
                "a store kind growth cannot start from is a chest glimpsed and never grown");
    }

    @Test
    void theHalfWithTheLOWERCoordinateIsTheAnchor() {
        assertEquals(Store.BLOCK, Store.kindFor(0, 0), "a barrel is joined to nothing");
        assertEquals(Store.BLOCK, Store.kindFor(1, 0), "the other half is +X, so this one is the anchor");
        assertEquals(Store.BLOCK, Store.kindFor(0, 1));
        assertEquals(Store.FAR_X, Store.kindFor(-1, 0), "the anchor is one cell back along X");
        assertEquals(Store.FAR_Z, Store.kindFor(0, -1));
    }

    @Test
    void bothHalvesJoinTheGrowthTheyBelongTo() {
        assertTrue(Store.RULE.joins(ANCHOR, Store.FAR_X, UNREAD),
                "a far half growth refuses is a half that can never be paired with its anchor");
        assertTrue(Store.RULE.joins(ANCHOR, Store.FAR_Z, UNREAD));
    }

    @Test
    void aDoubleStoreIsOnePlaceSpanningBothCells() {
        Pos far = new Pos(1, 64, 0);
        List<Evaluation> found = Store.RULE.evaluate(mass(ANCHOR, Store.BLOCK, far, Store.FAR_X), UNREAD);

        assertEquals(1, found.size(), "one chest, one place");
        assertEquals(List.of(ANCHOR), found.get(0).approach(),
                "one deterministic anchor, or two bodies found two claims on one chest");
        assertEquals(Set.of(ANCHOR, far), found.get(0).blocks().keySet());
        assertEquals(2, found.get(0).units());
    }

    @Test
    void aDoubleStoreJoinedAlongZIsOnePlaceToo() {
        Pos far = new Pos(0, 64, 1);
        assertEquals(Set.of(Set.of(ANCHOR, far)), thingsIn(mass(ANCHOR, Store.BLOCK, far, Store.FAR_Z)));
    }

    @Test
    void pairingDoesNotDependOnWhichHalfTheScanReachedFirst() {
        Pos far = new Pos(1, 64, 0);
        assertEquals(Set.of(Set.of(ANCHOR, far)), thingsIn(mass(far, Store.FAR_X, ANCHOR, Store.BLOCK)));
    }

    @Test
    void twoSingleChestsSideBySideStayTwoPlaces() {
        Pos next = new Pos(1, 64, 0);
        assertEquals(Set.of(Set.of(ANCHOR), Set.of(next)),
                thingsIn(mass(ANCHOR, Store.BLOCK, next, Store.BLOCK)));
    }

    @Test
    void chestsPairByTheHalfTheyNAMEAndNeverByAdjacency() {
        // Two double chests at right angles. The far half of A touches the anchor of B, and B's
        // anchor sorts BELOW A's by the very ordering the halves are named from — so anything
        // pairing a far half with its nearest or lowest neighbouring anchor mispairs both.
        Pos anchorA = new Pos(0, 64, 0);
        Pos farA = new Pos(1, 64, 0);
        Pos anchorB = new Pos(1, 64, -1);
        Pos farB = new Pos(2, 64, -1);
        Map<Pos, BlockKind> cells = new LinkedHashMap<>();
        cells.put(anchorA, Store.BLOCK);
        cells.put(farA, Store.FAR_X);
        cells.put(anchorB, Store.BLOCK);
        cells.put(farB, Store.FAR_X);

        assertEquals(Set.of(Set.of(anchorA, farA), Set.of(anchorB, farB)), thingsIn(cells));
    }

    @Test
    void aFarHalfWhoseAnchorFellOutsideTheScanStandsAlone() {
        Pos far = new Pos(1, 64, 0);
        Map<Pos, BlockKind> cells = new LinkedHashMap<>();
        cells.put(far, Store.FAR_X);

        assertEquals(Set.of(Set.of(far)), thingsIn(cells),
                "growth cut at the cap must leave a chest half-remembered, never unremembered");
    }

    @Test
    void standingAtTheFarHalfIsStillStandingAtTheStore() {
        FakeContext ctx = ctxBeside();
        ctx.percepts.blocks.set(CHEST.x(), CHEST.y(), CHEST.z(), Store.FAR_X);

        assertTrue(Store.standingAtOne(ctx));
        assertFalse(ctx.knowledge.all(Store.POI).isEmpty(),
                "a player joining their chest onto the party's must not cost the party the claim");
    }
}
