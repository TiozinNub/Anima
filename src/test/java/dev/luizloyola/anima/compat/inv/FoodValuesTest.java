package dev.luizloyola.anima.compat.inv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.inv.ItemStack;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The patch is decoded only when it names {@code FOOD}. These are the cases that shortcut could
 * get wrong: a patch that adds food, and one that takes it away.
 */
class FoodValuesTest {

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
        // Item defaults are bound by a server's resource load, not by Bootstrap. This is how
        // vanilla's own components report binds them without one.
        if (!Items.APPLE.builtInRegistryHolder().areComponentsBound()) {
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                    .forEach(DataComponentInitializers.PendingComponents::apply);
        }
    }

    private static ItemStack core(net.minecraft.world.item.ItemStack stack) {
        return ItemStacks.toCore(stack, registries);
    }

    @Test
    void anUnpatchedItemAnswersFromItsDefault() {
        ItemStack apple = core(new net.minecraft.world.item.ItemStack(Items.APPLE));
        assertEquals(4, FoodValues.of(apple, registries).orElseThrow().nutrition());
    }

    @Test
    void aWornAxeIsNotFoodWithoutItsPatchBeingRead() {
        net.minecraft.world.item.ItemStack axe = new net.minecraft.world.item.ItemStack(Items.WOODEN_AXE);
        axe.setDamageValue(12);
        ItemStack worn = core(axe);

        assertFalse(worn.components().isEmpty(), "the fixture has to carry a patch");
        assertFalse(ItemStacks.patchMentions(worn, DataComponents.FOOD));
        assertTrue(FoodValues.of(worn, registries).isEmpty());
    }

    @Test
    void aPatchThatAddsFoodIsStillRead() {
        net.minecraft.world.item.ItemStack stick = new net.minecraft.world.item.ItemStack(Items.STICK);
        stick.set(DataComponents.FOOD, new FoodProperties(2, 0.5f, false));

        assertEquals(2, FoodValues.of(core(stick), registries).orElseThrow().nutrition());
    }

    @Test
    void aPatchThatRemovesFoodIsStillRead() {
        net.minecraft.world.item.ItemStack apple = new net.minecraft.world.item.ItemStack(Items.APPLE);
        apple.remove(DataComponents.FOOD);

        assertTrue(FoodValues.of(core(apple), registries).isEmpty());
    }
}
