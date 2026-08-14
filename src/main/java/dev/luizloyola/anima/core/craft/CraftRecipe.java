package dev.luizloyola.anima.core.craft;

import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One way to make a thing from other things, as pure data — the {@code core} layer's view of a
 * crafting recipe, carrying no {@code net.minecraft}. The compat lens translates the game's
 * recipes into these.
 *
 * <p>The ingredient list is a <b>bill of materials</b>, not a grid: identical alternatives are
 * aggregated ("planks ×3, sticks ×2") because a gathering mind cares how many of what class, never
 * which pattern cell. Placement is the game's business at the moment of the craft.
 *
 * @param id          the recipe's own name, for readouts and journal lines — never parsed
 * @param output      what one craft yields, as a whole core stack — id and count and cap and any
 *                    components, so the act that performs the craft adds this
 * @param ingredients the bill, aggregated; never empty
 * @param needsTable  whether this craft exceeds the 2×2 a body can do in its own hands
 */
public record CraftRecipe(String id, ItemStack output,
                          List<Ingredient> ingredients, boolean needsTable) {

    /**
     * One line of the bill: {@code count} items from any of {@code acceptedIds}. The alternatives
     * ("any plank") are kept rather than flattened, so the gatherer may satisfy the line with
     * whatever mix it holds.
     */
    public record Ingredient(Set<String> acceptedIds, int count) {
        public Ingredient {
            Objects.requireNonNull(acceptedIds, "acceptedIds");
            if (acceptedIds.isEmpty()) {
                throw new IllegalArgumentException("an ingredient accepts at least one item");
            }
            if (count < 1) {
                throw new IllegalArgumentException("an ingredient is at least one item: " + count);
            }
            acceptedIds = Set.copyOf(acceptedIds);
        }

        /** Whether one item of this id can fill this line. */
        public boolean accepts(String itemId) {
            return acceptedIds.contains(itemId);
        }
    }

    public CraftRecipe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(ingredients, "ingredients");
        if (output.isEmpty()) {
            throw new IllegalArgumentException("a recipe produces something");
        }
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("a recipe consumes something");
        }
        ingredients = List.copyOf(ingredients);
    }

    /** The produced item's id — the half of {@link #output} every matcher asks about. */
    public String outputId() {
        return output.id();
    }

    public int outputCount() {
        return output.count();
    }
}
