package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;

/**
 * Anything edible, as an {@link ItemSpec}: what a party stocks and what hunting and foraging
 * produce (directions spec, decision 16). Whether a food is <em>ready</em> is a separate question,
 * {@link ReadyFood}'s, and only eating asks it — raw beef is food, and is eaten only when starving.
 * Reads the lookup {@link ReadyFood#install} was given; with none nothing matches.
 */
public final class Food {

    public static final ItemSpec SPEC = ItemSpec.register(new ItemSpec("food", Food::matches));

    private Food() {
    }

    private static boolean matches(String id) {
        FoodLookup lookup = ReadyFood.lookup();
        return lookup != null && lookup.of(ItemStack.of(id, 1, 64)).isPresent();
    }
}
