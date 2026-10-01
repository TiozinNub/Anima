package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;

/**
 * Food that cooking makes better — raw beef, a potato — as an {@link ItemSpec}: what a meal is
 * cooked from. Matched by what it is, never by name, so no producer of a named item answers it: a
 * hungry body cooks what it has and never hunts for a meal (directions spec, decision 17). Reads
 * the lookup {@link ReadyFood#install} was given; with none nothing matches.
 */
public final class RawFood {

    public static final ItemSpec SPEC = ItemSpec.register(new ItemSpec("raw_food", RawFood::matches));

    private RawFood() {
    }

    private static boolean matches(String id) {
        FoodLookup lookup = ReadyFood.lookup();
        return lookup != null && lookup.cookedForm(ItemStack.of(id, 1, 64)).isPresent();
    }
}
