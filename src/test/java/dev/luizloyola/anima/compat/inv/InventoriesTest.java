package dev.luizloyola.anima.compat.inv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.inv.HandChange;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link Inventories#CODEC}: a swap under way survives a save, and a nonsense one is dropped. */
class InventoriesTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Inventory roundTrip(Inventory inv) {
        return Inventories.CODEC.parse(JsonOps.INSTANCE,
                Inventories.CODEC.encodeStart(JsonOps.INSTANCE, inv).getOrThrow()).getOrThrow();
    }

    @Test
    void aSwapUnderWayIsSaved() {
        Inventory inv = new Inventory();
        inv.set(Inventory.MAIN_START, ItemStack.of("minecraft:iron_sword", 1, 1));
        inv.setSelectedSlot(4);
        HandChange change = new HandChange(HandChange.Kind.WIELD, Inventory.MAIN_START, -1, 1206L, 1201L);
        inv.setChange(change);

        Inventory back = roundTrip(inv);
        assertEquals(change.asRestored(), back.change(), "taken up by the first tick after the load");
        assertEquals(4, back.selectedSlot());
        assertEquals("minecraft:iron_sword", back.get(Inventory.MAIN_START).id());
    }

    @Test
    void noSwapIsNoSwap() {
        assertNull(roundTrip(new Inventory()).change());
    }

    @Test
    void aSwapFromNoSlotThereIsIsDropped() {
        Inventory inv = new Inventory();
        inv.setChange(new HandChange(HandChange.Kind.WIELD, 99, -1, 10L, 5L));
        assertNull(roundTrip(inv).change());
    }
}
