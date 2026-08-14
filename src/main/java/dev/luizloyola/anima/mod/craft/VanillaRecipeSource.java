package dev.luizloyola.anima.mod.craft;

import dev.luizloyola.anima.compat.craft.CraftingRecipes;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.craft.RecipeSource;
import dev.luizloyola.anima.core.craft.Recipes;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * The vanilla recipe book as a {@link RecipeSource}: snapshotted through {@link CraftingRecipes}
 * once per load and re-snapshotted on a datapack reload, so an agent never plans against a recipe
 * {@code /reload} just removed.
 *
 * <p>{@link #install()} is the consumer's one call, at mod initialization; idempotent, since two
 * mods installing must still register one source or every recipe counts twice.
 *
 * <p>Queries before a server exists answer empty — recipe data is datapack data and is not there
 * yet.
 */
public final class VanillaRecipeSource implements RecipeSource {

    private static @Nullable VanillaRecipeSource installed;

    private volatile @Nullable MinecraftServer server;
    private volatile @Nullable List<CraftRecipe> book;

    private VanillaRecipeSource() {
    }

    /** Registers the vanilla book with {@link Recipes}, once, whoever asks. */
    public static synchronized void install() {
        if (installed != null) {
            return;
        }
        VanillaRecipeSource source = new VanillaRecipeSource();
        installed = source;
        ServerLifecycleEvents.SERVER_STARTED.register(source::attach);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
                (server, resources, success) -> source.attach(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(source::detach);
        Recipes.provide(source);
    }

    private void attach(MinecraftServer started) {
        this.server = started;
        this.book = null; // fresh data; the next ask re-reads
    }

    private void detach(MinecraftServer stopped) {
        if (this.server == stopped) {
            this.server = null;
            this.book = null;
        }
    }

    @Override
    public List<CraftRecipe> producing(ItemSpec spec) {
        List<CraftRecipe> known = this.book;
        if (known == null) {
            MinecraftServer current = this.server;
            if (current == null) {
                return List.of();
            }
            known = CraftingRecipes.snapshot(current);
            this.book = known;
        }
        List<CraftRecipe> matching = new ArrayList<>();
        for (CraftRecipe recipe : known) {
            if (spec.matches(recipe.outputId())) {
                matching.add(recipe);
            }
        }
        return matching;
    }
}
