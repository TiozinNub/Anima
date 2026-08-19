package dev.luizloyola.anima.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
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
}
