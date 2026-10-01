package dev.luizloyola.anima.core.inv;

import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * How much of its durability a stack has left, read by the mod off the real item. Core never parses
 * {@link ItemStack#components()}, so the number crosses this seam instead. With nothing installed
 * nothing wears.
 *
 * <p>Wearing is also what makes a stack a tool here: there is no list of tool kinds.
 */
public final class Wear {

    /** The running server's answers. */
    public interface Lookup {
        /** The share of its durability {@code stack} has left, 0..1; empty for one that never wears. */
        OptionalDouble left(ItemStack stack);
    }

    private static volatile @Nullable Lookup lookup;

    private Wear() {
    }

    public static void install(@Nullable Lookup wear) {
        lookup = wear;
    }

    public static OptionalDouble left(ItemStack stack) {
        Lookup wear = lookup;
        return wear == null || stack.isEmpty() ? OptionalDouble.empty() : wear.left(stack);
    }

    /** Whether {@code stack} wears with use — a tool, a weapon, shears. */
    public static boolean wears(ItemStack stack) {
        return left(stack).isPresent();
    }
}
