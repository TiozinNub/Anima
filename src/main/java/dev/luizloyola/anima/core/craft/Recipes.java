package dev.luizloyola.anima.core.craft;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who knows how things are made — the registry {@code CraftFor} consults, the same shape as
 * {@link dev.luizloyola.anima.core.brain.task.Producers}: Anima plans a craft, but which recipes
 * exist belongs to the consuming mod, and a mod that registers none never reaches for a workbench.
 *
 * <p>Sources chain in registration order ({@code AgentDirectory.provide} discipline); a spec no
 * source can produce yields an empty list, which lets an {@code ObtainItem} fall back to
 * scavenging and felling.
 */
public final class Recipes {

    private static final List<RecipeSource> SOURCES = new CopyOnWriteArrayList<>();

    private Recipes() {
    }

    /** Teaches the brain where recipes come from. Call during mod initialization. */
    public static void provide(RecipeSource source) {
        SOURCES.add(source);
    }

    /** Every known recipe producing {@code spec}, across all sources in registration order. */
    public static List<CraftRecipe> producing(ItemSpec spec) {
        List<CraftRecipe> all = new ArrayList<>();
        for (RecipeSource source : SOURCES) {
            all.addAll(source.producing(spec));
        }
        return all;
    }

    /** Whether anybody at all supplies recipes — the cheap "is crafting a thing here" question. */
    public static boolean anyProvided() {
        return !SOURCES.isEmpty();
    }

    /** Forgets every source — test teardown only. */
    public static void reset() {
        SOURCES.clear();
    }
}
