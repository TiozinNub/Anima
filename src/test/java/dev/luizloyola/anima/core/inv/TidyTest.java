package dev.luizloyola.anima.core.inv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Swaps that settle a pack into its layout, and only swaps worth their time. */
class TidyTest {

    /** A sword belongs in slot 0, any axe on the hotbar, everything else in the backpack. */
    private static final PackLayout LAYOUT = new PackLayout() {
        @Override
        public double[] weights(ItemStack stack) {
            double[] fit = new double[Inventory.ARMOR_START];
            for (int slot = 0; slot < Inventory.MAIN_START; slot++) {
                fit[slot] = stack.id().endsWith("_sword") ? 10 - slot
                        : stack.id().endsWith("_axe") ? 3 : -5;
            }
            return fit;
        }

        @Override
        public int freeHotbar() {
            return 1;
        }
    };

    private static ItemStack one(String id) {
        return ItemStack.of("minecraft:" + id, 1, 1);
    }

    private static Inventory settle(Inventory pack) {
        for (Tidy.Swap swap : Tidy.plan(pack, LAYOUT, 20)) {
            ItemStack moved = pack.get(swap.from());
            pack.set(swap.from(), pack.get(swap.to()));
            pack.set(swap.to(), moved);
        }
        return pack;
    }

    @Test
    void logsLeaveTheHotbarAndTheSwordComesHome() {
        Inventory pack = new Inventory();
        pack.set(0, ItemStack.of("minecraft:oak_log", 64, 64));
        pack.set(20, one("iron_sword"));
        settle(pack);
        assertEquals("minecraft:iron_sword", pack.get(0).id());
        assertTrue(pack.get(20).id().equals("minecraft:oak_log")
                || pack.get(Inventory.MAIN_START).id().equals("minecraft:oak_log"));
        for (int slot = 0; slot < Inventory.MAIN_START; slot++) {
            assertTrue(!pack.get(slot).id().equals("minecraft:oak_log"), "no logs on the hotbar");
        }
    }

    @Test
    void aSettledPackHasNothingToDo() {
        Inventory pack = new Inventory();
        pack.set(0, one("iron_sword"));
        pack.set(3, one("stone_axe"));
        pack.set(12, ItemStack.of("minecraft:dirt", 64, 64));
        assertTrue(Tidy.next(pack, LAYOUT).isEmpty());
    }

    @Test
    void aFullHotbarOfToolsGivesOneUp() {
        Inventory pack = new Inventory();
        for (int slot = 0; slot < Inventory.MAIN_START; slot++) {
            pack.set(slot, one("stone_axe"));
        }
        settle(pack);
        assertEquals(1, pack.emptyHotbar());
    }

    @Test
    void aSmallGainIsNotWorthAMove() {
        Inventory pack = new Inventory();
        pack.set(1, one("iron_sword")); // one slot from home: worth 1, the floor
        pack.set(0, one("stone_axe"));
        PackLayout fussy = new PackLayout() {
            @Override
            public double[] weights(ItemStack stack) {
                return LAYOUT.weights(stack);
            }

            @Override
            public int freeHotbar() {
                return 1;
            }

            @Override
            public double minGain() {
                return 2.0;
            }
        };
        assertTrue(Tidy.next(pack, fussy).isEmpty());
    }

    @Test
    void aPlanStopsAtItsCap() {
        Inventory pack = new Inventory();
        for (int slot = 0; slot < Inventory.MAIN_START; slot++) {
            pack.set(slot, ItemStack.of("minecraft:oak_log", 1 + slot, 64));
        }
        List<Tidy.Swap> swaps = Tidy.plan(pack, LAYOUT, 3);
        assertEquals(3, swaps.size());
    }
}
