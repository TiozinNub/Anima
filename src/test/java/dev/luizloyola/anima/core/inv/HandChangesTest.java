package dev.luizloyola.anima.core.inv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link HandChanges}: a hotbar select is short, a backpack pull is a stack move, and both lapse. */
class HandChangesTest {

    private static final HandChanges.Timing TIMING = new HandChanges.Timing(2, 6);
    private static final int BACKPACK = Inventory.MAIN_START + 4;

    private final Inventory inv = new Inventory();

    private static ItemStack sword() {
        return ItemStack.of("minecraft:iron_sword", 1, 1);
    }

    private static ItemStack helmet(String material) {
        return ItemStack.of("minecraft:" + material + "_helmet", 1, 1);
    }

    /** Ticks from the first ask until the answer is true, asking every tick. */
    private int ticksToWield(int slot, long from) {
        for (long now = from; now < from + 50; now++) {
            if (HandChanges.wield(inv, slot, now, TIMING)) {
                return (int) (now - from);
            }
        }
        return -1;
    }

    @Test
    void aHotbarSlotIsASelectAway() {
        inv.set(3, sword());
        assertEquals(2, ticksToWield(3, 100));
        assertEquals(3, inv.selectedSlot());
        assertNull(inv.change());
    }

    @Test
    void aBackpackStackIsAStackMoveAway() {
        inv.set(BACKPACK, sword());
        assertEquals(6, ticksToWield(BACKPACK, 100));
        assertEquals("minecraft:iron_sword", inv.mainHand().id());
        assertTrue(inv.get(BACKPACK).isEmpty());
    }

    @Test
    void whatIsAlreadyInHandIsThereAtOnce() {
        inv.set(0, sword());
        assertTrue(HandChanges.wield(inv, 0, 100, TIMING));
    }

    @Test
    void theStackStaysWhereItIsUntilTheMoveIsDue() {
        inv.set(BACKPACK, sword());
        for (long now = 100; now < 105; now++) {
            assertFalse(HandChanges.wield(inv, BACKPACK, now, TIMING));
        }
        assertTrue(inv.mainHand().isEmpty());
        assertTrue(HandChanges.busy(inv, 105));
    }

    @Test
    void aTickNobodyAsksAbandonsTheMove() {
        inv.set(BACKPACK, sword());
        HandChanges.wield(inv, BACKPACK, 100, TIMING);
        HandChanges.wield(inv, BACKPACK, 101, TIMING);
        assertFalse(HandChanges.busy(inv, 103));
        assertEquals(6, ticksToWield(BACKPACK, 110), "asked again, the whole move is paid again");
    }

    @Test
    void aDifferentRequestReplacesTheMove() {
        inv.set(BACKPACK, sword());
        inv.set(5, ItemStack.of("minecraft:iron_axe", 1, 1));
        HandChanges.wield(inv, BACKPACK, 100, TIMING);
        HandChanges.wield(inv, BACKPACK, 101, TIMING);
        assertEquals(2, ticksToWield(5, 102), "one pair of hands: the axe starts from nothing");
        assertEquals("minecraft:iron_axe", inv.mainHand().id());
        assertEquals("minecraft:iron_sword", inv.get(BACKPACK).id());
    }

    @Test
    void instantTimingMovesOnTheFirstAsk() {
        inv.set(BACKPACK, sword());
        assertTrue(HandChanges.wield(inv, BACKPACK, 100, HandChanges.Timing.INSTANT));
        assertEquals("minecraft:iron_sword", inv.mainHand().id());
    }

    @Test
    void aMoveBetweenStorageSlotsIsAStackMove() {
        inv.set(0, ItemStack.of("minecraft:oak_log", 64, 64));
        inv.set(BACKPACK, sword());
        int ticks = -1;
        for (long now = 100; now < 150; now++) {
            if (HandChanges.move(inv, 0, BACKPACK, now, TIMING)) {
                ticks = (int) (now - 100);
                break;
            }
        }
        assertEquals(6, ticks);
        assertEquals("minecraft:iron_sword", inv.get(0).id());
        assertEquals("minecraft:oak_log", inv.get(BACKPACK).id());
    }

    @Test
    void stowingOntoAFreeHotbarSlotIsASelect() {
        inv.set(0, sword());
        assertFalse(HandChanges.stow(inv, 100, TIMING));
        assertFalse(HandChanges.stow(inv, 101, TIMING));
        assertTrue(HandChanges.stow(inv, 102, TIMING));
        assertTrue(inv.mainHand().isEmpty());
        assertEquals("minecraft:iron_sword", inv.get(0).id(), "put away, not dropped");
    }

    @Test
    void stowingIntoTheBackpackIsAStackMove() {
        for (int slot = 0; slot < Inventory.HOTBAR_SIZE; slot++) {
            inv.set(slot, ItemStack.of("minecraft:dirt", 1, 64));
        }
        long now = 100;
        while (!HandChanges.stow(inv, now, TIMING)) {
            now++;
        }
        assertEquals(106, now);
        assertTrue(inv.mainHand().isEmpty());
    }

    @Test
    void aFullPackCannotStowAndSaysSoAtOnce() {
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            inv.set(slot, ItemStack.of("minecraft:dirt", 1, 64));
        }
        assertTrue(HandChanges.stow(inv, 100, TIMING));
        assertFalse(inv.mainHand().isEmpty());
        assertNull(inv.change());
    }

    @Test
    void equippingTakesAStackMoveAndPutsTheOldPieceWhereTheNewOneWas() {
        inv.setArmor(ArmorType.HEAD, helmet("leather"));
        inv.set(BACKPACK, helmet("iron"));
        long now = 100;
        while (!HandChanges.equip(inv, BACKPACK, ArmorType.HEAD, now, TIMING)) {
            now++;
        }
        assertEquals(106, now);
        assertEquals("minecraft:iron_helmet", inv.armor(ArmorType.HEAD).id());
        assertEquals("minecraft:leather_helmet", inv.get(BACKPACK).id());
    }

    @Test
    void whatADrawStillCosts() {
        inv.set(3, sword());
        inv.set(BACKPACK, sword());
        assertEquals(0, HandChanges.ticksToWield(inv, 0, 100, TIMING), "held");
        assertEquals(2, HandChanges.ticksToWield(inv, 3, 100, TIMING));
        assertEquals(6, HandChanges.ticksToWield(inv, BACKPACK, 100, TIMING));
        HandChanges.wield(inv, BACKPACK, 100, TIMING);
        HandChanges.wield(inv, BACKPACK, 101, TIMING);
        assertEquals(4, HandChanges.ticksToWield(inv, BACKPACK, 102, TIMING), "the rest of it");
        assertEquals(2, HandChanges.ticksToWield(inv, 3, 102, TIMING), "another move, whole");
        assertEquals(0, HandChanges.ticksToStow(inv, 102, TIMING), "the hand is empty");
    }

    @Test
    void aRestoredMoveSurvivesTheTicksAReloadSkips() {
        inv.set(BACKPACK, sword());
        HandChanges.wield(inv, BACKPACK, 100, TIMING);
        HandChanges.wield(inv, BACKPACK, 101, TIMING); // saved here: due at 106
        inv.setChange(inv.change().asRestored());

        assertTrue(HandChanges.busy(inv, 105), "three ticks later, still the same draw");
        long now = 105;
        while (!HandChanges.wield(inv, BACKPACK, now, TIMING)) {
            now++;
        }
        assertEquals(106, now, "and it lands when it was due");
    }

    @Test
    void aRestoredMoveNobodyTakesUpStillLapses() {
        inv.set(BACKPACK, sword());
        HandChanges.wield(inv, BACKPACK, 100, TIMING);
        inv.setChange(inv.change().asRestored());

        assertTrue(HandChanges.busy(inv, 120));
        assertFalse(HandChanges.busy(inv, 122), "taken up at 120, then asked by nobody");
    }

    @Test
    void aCopyCarriesTheMoveUnderWay() {
        inv.set(BACKPACK, sword());
        HandChanges.wield(inv, BACKPACK, 100, TIMING);
        Inventory restored = new Inventory();
        restored.copyFrom(inv);
        long now = 101;
        while (!HandChanges.wield(restored, BACKPACK, now, TIMING)) {
            now++;
        }
        assertEquals(106, now, "a restart mid-draw finishes the same draw, not a new one");
    }
}
