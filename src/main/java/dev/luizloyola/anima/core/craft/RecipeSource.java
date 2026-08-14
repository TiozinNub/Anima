package dev.luizloyola.anima.core.craft;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;

/**
 * Somewhere recipes come from — the seam between the pure recipe model and whatever supplies it
 * (the vanilla lens over the game's recipe data, a test's hand-built list). Registered through
 * {@link Recipes} at bootstrap; a mod that registers none has no crafting, since
 * {@code CraftFor} is never applicable.
 */
public interface RecipeSource {

    /**
     * Every known recipe whose output matches {@code spec}, in the source's own order. Empty is a
     * legitimate answer, not an error — most specs are not craftable, and "no recipe" is exactly
     * what an {@code ObtainItem} degrading to scavenging needs to hear.
     */
    List<CraftRecipe> producing(ItemSpec spec);
}
