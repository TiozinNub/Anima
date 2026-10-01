package dev.luizloyola.anima.core.inv;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which swaps settle a pack into its {@link PackLayout}: the best single swap at a time, each worth
 * at least {@link PackLayout#minGain()}. Greedy and re-asked after every move, so a pack changed
 * mid-tidy is planned from what it holds now.
 */
public final class Tidy {

    /**
     * What a free hotbar slot short of {@link PackLayout#freeHotbar()} costs: a rule, not a taste,
     * so it outweighs any one stack's weight.
     */
    static final double SHORT_OF_FREE = 1000.0;

    /** Two storage slots to swap, and what the swap gains. */
    public record Swap(int from, int to, double gain) {
    }

    private Tidy() {
    }

    /** The best swap worth making now, if any. */
    public static Optional<Swap> next(Inventory pack, PackLayout layout) {
        double[][] fit = new double[Inventory.ARMOR_START][];
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ItemStack stack = pack.get(slot);
            fit[slot] = stack.isEmpty() ? null : layout.weights(stack, pack);
        }
        int shortNow = Math.max(0, layout.freeHotbar() - pack.emptyHotbar());
        Swap best = null;
        for (int a = 0; a < Inventory.ARMOR_START; a++) {
            for (int b = a + 1; b < Inventory.ARMOR_START; b++) {
                if (fit[a] == null && fit[b] == null || same(pack.get(a), pack.get(b))) {
                    continue;
                }
                double gain = at(fit[a], b) + at(fit[b], a) - at(fit[a], a) - at(fit[b], b);
                gain += SHORT_OF_FREE * (shortNow - shortAfter(pack, layout, a, b));
                if (gain >= layout.minGain() && (best == null || gain > best.gain())) {
                    best = new Swap(a, b, gain);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Up to {@code max} swaps in order, planned on a copy — what a tidy would do from here. */
    public static List<Swap> plan(Inventory pack, PackLayout layout, int max) {
        Inventory copy = new Inventory();
        copy.copyFrom(pack);
        List<Swap> swaps = new ArrayList<>();
        while (swaps.size() < max) {
            Optional<Swap> swap = next(copy, layout);
            if (swap.isEmpty()) {
                break;
            }
            ItemStack moved = copy.get(swap.get().from());
            copy.set(swap.get().from(), copy.get(swap.get().to()));
            copy.set(swap.get().to(), moved);
            swaps.add(swap.get());
        }
        return swaps;
    }

    private static double at(double[] fit, int slot) {
        return fit == null ? 0.0 : fit[slot];
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.id().equals(b.id()) && a.components().equals(b.components()) && a.count() == b.count();
    }

    /** How many free hotbar slots short the pack would be with {@code a} and {@code b} swapped. */
    private static int shortAfter(Inventory pack, PackLayout layout, int a, int b) {
        int empty = pack.emptyHotbar();
        boolean aHot = a < Inventory.MAIN_START;
        boolean bHot = b < Inventory.MAIN_START;
        if (aHot != bHot) {
            int hot = aHot ? a : b;
            int cold = aHot ? b : a;
            empty += (pack.get(hot).isEmpty() ? -1 : 0) + (pack.get(cold).isEmpty() ? 1 : 0);
        }
        return Math.max(0, layout.freeHotbar() - empty);
    }
}
