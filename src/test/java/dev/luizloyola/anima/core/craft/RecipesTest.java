package dev.luizloyola.anima.core.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The recipe registry and the pure model, headless. The vanilla lens is not here —
 * recipes are datapack data, invisible to a bootstrapped unit test (the same hole tags fall
 * into), so the lens proves itself on the dev server and these tests pin everything around it.
 */
class RecipesTest {

    private static final ItemSpec AXES =
            ItemSpec.register(new ItemSpec("recipes-test-axes", id -> id.endsWith("_axe")));

    private static CraftRecipe woodenAxe() {
        return new CraftRecipe("minecraft:wooden_axe", "minecraft:wooden_axe", 1,
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:oak_planks",
                                "minecraft:birch_planks"), 3),
                        new CraftRecipe.Ingredient(Set.of("minecraft:stick"), 2)),
                true);
    }

    @AfterEach
    void tearDown() {
        Recipes.reset();
    }

    @Test
    void producingFiltersAcrossSourcesInRegistrationOrder() {
        CraftRecipe axe = woodenAxe();
        CraftRecipe planks = new CraftRecipe("minecraft:oak_planks", "minecraft:oak_planks", 4,
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:oak_log"), 1)), false);
        Recipes.provide(spec -> spec.matches(axe.outputId()) ? List.of(axe) : List.of());
        Recipes.provide(spec -> spec.matches(planks.outputId()) ? List.of(planks) : List.of());

        assertEquals(List.of(axe), Recipes.producing(AXES));
        assertTrue(Recipes.anyProvided());
    }

    @Test
    void noSourcesMeansNoRecipesAndNoError() {
        assertEquals(List.of(), Recipes.producing(AXES),
                "a mod that never registered a source simply has no crafting");
        assertFalse(Recipes.anyProvided());
    }

    @Test
    void ingredientAcceptsAnyOfItsAlternatives() {
        CraftRecipe.Ingredient planks = woodenAxe().ingredients().get(0);
        assertTrue(planks.accepts("minecraft:birch_planks"));
        assertFalse(planks.accepts("minecraft:stick"));
    }

    @Test
    void theModelRefusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new CraftRecipe("r", "", 1,
                woodenAxe().ingredients(), false));
        assertThrows(IllegalArgumentException.class, () -> new CraftRecipe("r", "minecraft:stick", 0,
                woodenAxe().ingredients(), false));
        assertThrows(IllegalArgumentException.class, () -> new CraftRecipe("r", "minecraft:stick", 1,
                List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new CraftRecipe.Ingredient(Set.of(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CraftRecipe.Ingredient(Set.of("minecraft:stick"), 0));
    }
}
