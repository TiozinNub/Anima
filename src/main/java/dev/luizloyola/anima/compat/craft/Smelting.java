package dev.luizloyola.anima.compat.craft;

import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.brain.sense.SmeltLookup;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.mixin.AbstractFurnaceBlockEntityInvoker;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/**
 * {@link SmeltLookup} over the running server: smelting recipes for what a furnace makes, campfire
 * recipes for what a campfire does, and the furnace's own rule for how long a fuel burns. Memoized
 * against the recipe manager instance, which a {@code /reload} replaces (the rule
 * {@code CookedForms} keeps). Server-thread confined.
 */
public final class Smelting implements SmeltLookup {

    private static RecipeManager cachedAgainst;
    private static final Map<String, Optional<Smelt>> SMELTS = new HashMap<>();
    private static final Map<String, Optional<Smelt>> CAMPFIRE = new HashMap<>();
    private static final Map<String, Integer> LONGEST = new HashMap<>();
    private static final Map<String, Integer> SHORTEST_BURN = new HashMap<>();

    private final MinecraftServer server;

    public Smelting(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public Optional<Smelt> of(String inputId) {
        fresh();
        return SMELTS.computeIfAbsent(inputId, id -> scan(id, RecipeType.SMELTING));
    }

    @Override
    public Optional<Smelt> campfire(String inputId) {
        fresh();
        return CAMPFIRE.computeIfAbsent(inputId, id -> scan(id, RecipeType.CAMPFIRE_COOKING));
    }

    @Override
    public int smeltTicks(ItemSpec input) {
        fresh();
        return LONGEST.computeIfAbsent(input.name(), name -> {
            int longest = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                if (input.matches(id)) {
                    longest = Math.max(longest, of(id).map(Smelt::ticks).orElse(0));
                }
            }
            return longest;
        });
    }

    @Override
    public int burnTicks(ItemSpec fuel) {
        fresh();
        return SHORTEST_BURN.computeIfAbsent(fuel.name(), name -> {
            HolderLookup.Provider registries = server.registryAccess();
            FurnaceBlockEntity furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
            int shortest = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                if (!fuel.matches(id)) {
                    continue;
                }
                net.minecraft.world.item.ItemStack stack =
                        ItemStacks.toVanilla(ItemStack.of(id, 1, 64), registries);
                //? if >=26.3 {
                /*int burn = ((AbstractFurnaceBlockEntityInvoker) furnace).anima$burnDuration(server.overworld(), stack);
                *///?} else {
                int burn = ((AbstractFurnaceBlockEntityInvoker) furnace).anima$burnDuration(server.fuelValues(), stack);
                //?}
                if (burn > 0 && (shortest == 0 || burn < shortest)) {
                    shortest = burn;
                }
            }
            return shortest;
        });
    }

    private void fresh() {
        RecipeManager recipes = server.getRecipeManager();
        if (recipes != cachedAgainst) {
            SMELTS.clear();
            CAMPFIRE.clear();
            LONGEST.clear();
            SHORTEST_BURN.clear();
            cachedAgainst = recipes;
        }
    }

    private Optional<Smelt> scan(String id, RecipeType<?> type) {
        HolderLookup.Provider registries = server.registryAccess();
        net.minecraft.world.item.ItemStack raw = ItemStacks.toVanilla(ItemStack.of(id, 1, 1), registries);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)
                    || cooking.getType() != type || !cooking.input().test(raw)) {
                continue;
            }
            //? if >=26.1 {
            net.minecraft.world.item.ItemStack made = cooking.assemble(new SingleRecipeInput(raw));
            //?} else {
            /*net.minecraft.world.item.ItemStack made = cooking.assemble(new SingleRecipeInput(raw), registries);
            *///?}
            if (!made.isEmpty()) {
                return Optional.of(new Smelt(BuiltInRegistries.ITEM.getKey(made.getItem()).toString(),
                        cooking.cookingTime()));
            }
        }
        return Optional.empty();
    }
}
