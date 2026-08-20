package dev.luizloyola.anima.core.inv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What nobody has spoken for — the number both halves of the stow machinery bid on. */
class SurplusTest {

    private static final ItemSpec SHOVELS = ItemSpec.anyOf(Set.of("minecraft:stone_shovel"));
    private static final ItemSpec AXES = ItemSpec.anyOf(Set.of("minecraft:stone_axe"));
    private static final ItemSpec LOGS = ItemSpec.anyOf(Set.of("minecraft:oak_log"));

    /** Nothing is edible unless a test says so. */
    private static boolean nothingEdible(ItemStack stack) {
        return false;
    }

    @Test
    void aCallKeepsItsCountAndNoMore() {
        Inventory pack = new Inventory();
        pack.set(0, ItemStack.of("minecraft:stone_shovel", 1, 1));
        pack.set(1, ItemStack.of("minecraft:stone_shovel", 1, 1));
        pack.set(2, ItemStack.of("minecraft:stone_shovel", 1, 1));

        assertEquals(List.of(1, 2), Surplus.slots(pack,
                        List.of(ItemCall.need(SHOVELS, 1)), SurplusTest::nothingEdible),
                "one shovel is spoken for; the other two are cargo");
    }

    @Test
    void aFullPackOfSpokenForGoodsHasNoSurplusAtAll() {
        Inventory pack = new Inventory();
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            pack.set(slot, ItemStack.of("minecraft:oak_log", 64, 64));
        }

        assertTrue(Surplus.slots(pack,
                        List.of(ItemCall.need(LOGS, 64 * Inventory.ARMOR_START)),
                        SurplusTest::nothingEdible).isEmpty(),
                "36 slots full and not one of them cargo");
    }

    @Test
    void foodIsKeptByRuleRatherThanByACall() {
        Inventory pack = new Inventory();
        pack.set(0, ItemStack.of("minecraft:bread", 8, 64));
        pack.set(1, ItemStack.of("minecraft:oak_log", 64, 64));

        assertEquals(List.of(1), Surplus.slots(pack, List.of(),
                        stack -> stack.id().equals("minecraft:bread")),
                "nothing named the bread and it stays anyway");
    }

    @Test
    void wornArmourAndTheOffhandAreNotCargo() {
        Inventory pack = new Inventory();
        pack.set(Inventory.ARMOR_START, ItemStack.of("minecraft:iron_helmet", 1, 1));
        pack.set(Inventory.OFFHAND_SLOT, ItemStack.of("minecraft:shield", 1, 1));

        assertTrue(Surplus.slots(pack, List.of(), SurplusTest::nothingEdible).isEmpty(),
                "what a settler has ON is not what they are carrying to the chest");
    }

    @Test
    void twoCallsForOneSpecAreTwoClaims() {
        Inventory pack = new Inventory();
        pack.set(0, ItemStack.of("minecraft:stone_shovel", 1, 1));
        pack.set(1, ItemStack.of("minecraft:stone_shovel", 1, 1));

        assertTrue(Surplus.slots(pack,
                        List.of(ItemCall.need(SHOVELS, 1), ItemCall.need(SHOVELS, 1)),
                        SurplusTest::nothingEdible).isEmpty(),
                "merging them by spec would quietly halve what a body keeps");
    }

    @Test
    void nothingNamedMeansEverythingIsCargo() {
        Inventory pack = new Inventory();
        pack.set(0, ItemStack.of("minecraft:stone_axe", 1, 1));
        pack.set(3, ItemStack.of("minecraft:dirt", 64, 64));

        assertEquals(List.of(0, 3), Surplus.slots(pack, List.of(), SurplusTest::nothingEdible),
                "an unreserved axe is cargo — a settler keeps what something asked them to keep");
    }

    @Test
    void emptySlotsCountsStorageOnly() {
        Inventory pack = new Inventory();
        pack.set(0, ItemStack.of("minecraft:oak_log", 64, 64));
        pack.set(Inventory.ARMOR_START, ItemStack.of("minecraft:iron_helmet", 1, 1));

        assertEquals(Inventory.ARMOR_START - 1, Surplus.emptySlots(pack),
                "armour occupies no storage");
    }

    @Test
    void aRankedListServesTheBetterCallFirst() {
        Inventory pack = new Inventory();
        pack.set(0, ItemStack.of("minecraft:stone_axe", 1, 1));
        pack.set(1, ItemStack.of("minecraft:stone_shovel", 1, 1));

        assertTrue(Surplus.slots(pack,
                        List.of(ItemCall.need(AXES, 1), ItemCall.need(SHOVELS, 1)),
                        SurplusTest::nothingEdible).isEmpty(),
                "both are named, so neither is cargo whatever the order");
    }
}
