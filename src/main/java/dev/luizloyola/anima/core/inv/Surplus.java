package dev.luizloyola.anima.core.inv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What nobody has spoken for. A pack is not "full" because it holds a lot — it is full of
 * <em>cargo</em> when it holds things no errand, no standing want and no appetite has a claim on,
 * and that is the number the stow machinery bids on (decision: Luiz, 2026-08-20). A settler with
 * five tools, a meal and the blocks for a house they are about to build is using their pack, not
 * burdened by it.
 *
 * <p><b>Storage only.</b> Worn armour and the offhand are what a settler has ON; the 36 hotbar and
 * backpack slots are what they are carrying to the chest. The same line {@code PutItems} draws.
 *
 * <p><b>A call keeps its count and no more.</b> {@code need(shovels, 1)} against three shovels
 * spares one and leaves two as cargo, which is how five tools resolve without anything in core
 * having to know what a tool is.
 *
 * <p><b>Food is kept by rule, not by a call.</b> Edibility is a run-time question — a registry
 * read through {@code Percepts.foods()} — rather than a spec, so it arrives here as a predicate.
 * v1 keeps all of it.
 */
public final class Surplus {

    private Surplus() {
    }

    /**
     * The storage slots holding cargo, in slot order.
     *
     * @param reserved what is spoken for, best first — an earlier call is served first when two of
     *     them could claim the same stack, which is the whole of the tiering
     * @param edible whether a stack is food, asked per stack and never cached
     */
    public static List<Integer> slots(Inventory pack, List<ItemCall> reserved,
            Predicate<ItemStack> edible) {
        // How much of each call is still unspent. Keyed by the call rather than by its spec: two
        // calls naming the same spec are two claims, and merging them would halve what is kept.
        Map<ItemCall, Integer> unspent = new HashMap<>();
        for (ItemCall call : reserved) {
            unspent.merge(call, call.count(), Integer::sum);
        }
        List<Integer> cargo = new ArrayList<>();
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ItemStack held = pack.get(slot);
            if (held.isEmpty() || edible.test(held)) {
                continue;
            }
            if (!spend(unspent, reserved, held)) {
                cargo.add(slot);
            }
        }
        return List.copyOf(cargo);
    }

    /**
     * Charges this stack against the first call that still wants it, best first. True when
     * something claimed it — a partially-claimed stack counts as claimed, because a slot is the
     * unit that gets carried and half a slot cannot be left behind.
     */
    private static boolean spend(Map<ItemCall, Integer> unspent, List<ItemCall> reserved,
            ItemStack held) {
        for (ItemCall call : reserved) {
            int left = unspent.getOrDefault(call, 0);
            if (left <= 0 || !call.spec().matches(held.id())) {
                continue;
            }
            unspent.put(call, left - Math.min(left, held.count()));
            return true;
        }
        return false;
    }

    /** Empty STORAGE slots — the room a settler actually has for what they are about to pick up. */
    public static int emptySlots(Inventory pack) {
        int empty = 0;
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            if (pack.get(slot).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }
}
