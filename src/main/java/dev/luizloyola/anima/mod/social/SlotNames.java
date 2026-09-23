package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.Slot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A history slot as words — a component, so each reader's client names it in their own language.
 * An item or species this install does not know reads as "something" rather than as the
 * registry's default (air, a pig).
 */
public final class SlotNames {

    private SlotNames() {
    }

    public static Component of(Slot slot) {
        return switch (slot.type()) {
            case LANG -> Component.translatable(slot.value());
            case ITEM -> item(slot.value());
            case ENTITY -> entity(slot.value());
        };
    }

    private static Component item(String id) {
        Identifier key = Identifier.tryParse(id);
        Item item = key == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(key);
        return item == Items.AIR ? something() : new ItemStack(item).getHoverName();
    }

    private static Component entity(String species) {
        Identifier key = Identifier.tryParse(species);
        if (key == null) {
            return something();
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(key);
        return key.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type))
                ? type.getDescription() : something();
    }

    private static Component something() {
        return Component.translatable(Doings.SOMETHING.value());
    }
}
