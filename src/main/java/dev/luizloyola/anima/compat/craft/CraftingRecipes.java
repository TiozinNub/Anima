package dev.luizloyola.anima.compat.craft;

import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * The lens from the game's recipe data to the pure {@link CraftRecipe} model — the one place that
 * knows what a {@code RecipeManager} is. A lens, not a table, so datapack and modded recipes come
 * through for free (decision: Luiz); one that throws while being read is skipped, not fatal.
 *
 * <p>Only ordinary crafting: {@code isSpecial()} recipes and anything {@code PlacementInfo} calls
 * impossible to place are skipped, since a bill cannot describe them. The output is read by
 * assembling against the empty input, bytecode-verified to return the recipe's result untouched
 * for every non-special recipe.
 */
public final class CraftingRecipes {

    private CraftingRecipes() {
    }

    /** Every ordinary crafting recipe the server currently knows, as bills of materials. */
    public static List<CraftRecipe> snapshot(MinecraftServer server) {
        List<CraftRecipe> out = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            try {
                CraftRecipe mapped = map(holder, server);
                if (mapped != null) {
                    out.add(mapped);
                }
            } catch (RuntimeException broken) {
                // One unreadable (usually modded) recipe; the rest of the book still counts.
            }
        }
        return List.copyOf(out);
    }

    private static CraftRecipe map(RecipeHolder<?> holder, MinecraftServer server) {
        if (!(holder.value() instanceof CraftingRecipe crafting) || crafting.isSpecial()) {
            return null;
        }
        PlacementInfo placement = crafting.placementInfo();
        if (placement.isImpossibleToPlace()) {
            return null;
        }
        List<CraftRecipe.Ingredient> bill = bill(placement.ingredients());
        if (bill == null) {
            return null; // an ingredient nothing satisfies — not craftable in this world
        }
        String id = holder.id().identifier().toString();
        net.minecraft.world.item.ItemStack result;
        //? if >=26.1 {
        result = crafting.assemble(CraftingInput.EMPTY);
        //?} else {
        /*result = crafting.assemble(CraftingInput.EMPTY, server.registryAccess());
        *///?}
        if (result.isEmpty()) {
            return null;
        }
        // The whole output crosses (id, count, cap, components), so the craft act adds exactly
        // what the game would have made, including a modded recipe's data.
        return new CraftRecipe(id, ItemStacks.toCore(result, server.registryAccess()),
                bill, needsTable(crafting, placement));
    }

    /** The grid as a bill: identical alternative-sets aggregate into one counted line. */
    private static List<CraftRecipe.Ingredient> bill(List<Ingredient> ingredients) {
        Map<Set<String>, Integer> lines = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            Set<String> ids = new TreeSet<>();
            ingredient.items().forEach(item ->
                    ids.add(BuiltInRegistries.ITEM.getKey(item.value()).toString()));
            if (ids.isEmpty()) {
                return null;
            }
            lines.merge(Set.copyOf(ids), 1, Integer::sum);
        }
        List<CraftRecipe.Ingredient> bill = new ArrayList<>(lines.size());
        for (Map.Entry<Set<String>, Integer> line : lines.entrySet()) {
            bill.add(new CraftRecipe.Ingredient(line.getKey(), line.getValue()));
        }
        return bill;
    }

    /** Whether the craft exceeds the 2×2 a body manages in its own hands. */
    private static boolean needsTable(CraftingRecipe crafting, PlacementInfo placement) {
        if (crafting instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        return placement.ingredients().size() > 4;
    }
}
