package dev.luizloyola.anima.compat.inv;

import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.agent.FoodValue;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Reads an item's food payload into the core {@link FoodValue} from the item's own {@code FOOD}
 * data component, not a hardcoded table — so every vanilla food, and every modded one declaring
 * the component, works at its author's numbers.
 *
 * <p>Verbatim (verified against the 26.1.2 bytecode): {@code saturation} is already the value
 * {@code Metabolism.eat} expects — no modifier math on this side.
 */
public final class FoodValues {
    private FoodValues() {}

    /**
     * The food payload of {@code stack}'s underlying item, or empty for a non-food (or empty)
     * stack. A component patch can add, change or remove {@code FOOD}, so a patch that names it is
     * decoded; one that does not cannot change it, and the item's default answers.
     *
     * <p>Decoding every patch was 3.9% of a 90-body tick (2026-09-27): each settler's damaged axe,
     * asked every tick whether it was food.
     */
    public static Optional<FoodValue> of(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return Optional.empty();
        FoodProperties food = ItemStacks.patchMentions(stack, DataComponents.FOOD)
                ? ItemStacks.toVanilla(stack, registries).get(DataComponents.FOOD)
                : defaultFood(stack);
        if (food == null) return Optional.empty();
        return Optional.of(new FoodValue(food.nutrition(), food.saturation(), food.canAlwaysEat()));
    }

    private static @Nullable FoodProperties defaultFood(ItemStack stack) {
        Item item = ItemStacks.itemOrNull(stack.id());
        return item == null ? null : item.components().get(DataComponents.FOOD);
    }
}
