package dev.luizloyola.anima.compat.inv;

import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.OptionalDouble;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;

/** Durability and tool kind off the real item, for {@link dev.luizloyola.anima.core.inv.Wear}. */
public final class ToolWear {
    private ToolWear() {}

    /**
     * The share of its durability {@code stack} has left. Only a patch that touches wear is decoded:
     * a fresh tool's patch is empty, and decoding every patch cost 3.9% of a crowd's tick once
     * ({@link FoodValues}).
     */
    public static OptionalDouble left(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return OptionalDouble.empty();
        boolean patched = ItemStacks.patchMentions(stack, DataComponents.DAMAGE)
                || ItemStacks.patchMentions(stack, DataComponents.MAX_DAMAGE)
                || ItemStacks.patchMentions(stack, DataComponents.UNBREAKABLE);
        if (patched) return left(ItemStacks.toVanilla(stack, registries));
        Item item = ItemStacks.itemOrNull(stack.id());
        return item == null ? OptionalDouble.empty() : left(new net.minecraft.world.item.ItemStack(item));
    }

    public static OptionalDouble left(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return OptionalDouble.empty();
        int max = stack.getMaxDamage();
        return OptionalDouble.of((double) (max - stack.getDamageValue()) / max);
    }

    /**
     * What the stack is for, by vanilla tag, so a stone and a wooden axe are one kind and a sword
     * another. An item in none of the tags is a kind of its own.
     */
    public static String kind(net.minecraft.world.item.ItemStack stack) {
        if (stack.is(ItemTags.PICKAXES)) return "pickaxe";
        if (stack.is(ItemTags.AXES)) return "axe";
        if (stack.is(ItemTags.SHOVELS)) return "shovel";
        if (stack.is(ItemTags.HOES)) return "hoe";
        if (stack.is(ItemTags.SWORDS)) return "sword";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
