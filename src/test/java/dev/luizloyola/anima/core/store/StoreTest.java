package dev.luizloyola.anima.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
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
}
