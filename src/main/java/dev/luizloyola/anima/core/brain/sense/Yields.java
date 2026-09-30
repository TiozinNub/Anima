package dev.luizloyola.anima.core.brain.sense;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What killing a creature can drop, by species ({@link Being#species}) — its loot table, read by
 * the mod while a server runs. A hunt names its prey by what it wants from it, so this is asked
 * of remembered herds as well as of bodies in view. With nothing installed nothing drops anything.
 */
public final class Yields {

    /** The running server's answers. */
    public interface Lookup {
        /** Every item id an entry of the species' loot table can drop; empty for anything else. */
        Set<String> of(String species);

        /** Every species that can be hunted at all. */
        Set<String> species();
    }

    private static volatile @Nullable Lookup lookup;

    private Yields() {
    }

    public static void install(@Nullable Lookup drops) {
        lookup = drops;
    }

    public static Set<String> of(String species) {
        Lookup drops = lookup;
        return drops == null || species.isEmpty() ? Set.of() : drops.of(species);
    }

    public static Set<String> species() {
        Lookup drops = lookup;
        return drops == null ? Set.of() : drops.species();
    }
}
