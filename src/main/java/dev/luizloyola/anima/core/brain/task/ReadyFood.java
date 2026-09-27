package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What a body eats at ordinary hunger — {@link EatSelection}'s ready tier — as an {@link ItemSpec},
 * so a store can be searched for it. A spec sees only an item id and what is food is the registry's
 * answer, so the mod installs a lookup while a server runs; with none installed nothing matches.
 */
public final class ReadyFood {

    private static volatile @Nullable FoodLookup foods;

    public static final ItemSpec SPEC =
            ItemSpec.register(new ItemSpec("ready_food", ReadyFood::matches));

    private ReadyFood() {
    }

    public static void install(@Nullable FoodLookup lookup) {
        foods = lookup;
    }

    /** Edible, with no better cooked form, and not a treat saved for starving. */
    static boolean isReady(FoodLookup lookup, ItemStack stack) {
        return lookup.of(stack).filter(food -> !EatSelection.isLastResort(lookup, food, stack))
                .isPresent();
    }

    private static boolean matches(String id) {
        FoodLookup lookup = foods;
        // The stack size is never read: food values and cooked forms are looked up by id.
        return lookup != null && isReady(lookup, ItemStack.of(id, 1, 64));
    }
}
