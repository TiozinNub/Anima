package dev.luizloyola.anima.core.craft;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One way to make a thing from other things, as pure data — the {@code core} view of a crafting
 * recipe, with no {@code net.minecraft}; the compat lens translates the game's recipes into these.
 *
 * <p>The ingredient list is a <b>bill of materials</b>, not a grid: identical alternatives are
 * aggregated, because a gatherer cares how many of what class, not which pattern cell.
 *
 * @param id          the recipe's own name, for readouts — never parsed
 * @param outputId    namespaced item id produced
 * @param outputCount how many per craft, {@code >= 1}
 * @param ingredients the bill, aggregated; never empty
 * @param needsTable  whether it exceeds the 2×2 a body can do in hand
 */
public record CraftRecipe(String id, String outputId, int outputCount,
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
        Objects.requireNonNull(outputId, "outputId");
        Objects.requireNonNull(ingredients, "ingredients");
        if (outputId.isBlank()) {
            throw new IllegalArgumentException("a recipe produces something");
        }
        if (outputCount < 1) {
            throw new IllegalArgumentException("output count must be >= 1: " + outputCount);
        }
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("a recipe consumes something");
        }
        ingredients = List.copyOf(ingredients);
    }
}
