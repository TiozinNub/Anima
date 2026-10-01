package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Optional;

/**
 * Putting food on a campfire, one item to a slot. Nothing comes back through here: a campfire drops
 * what it cooked beside it, and the body picks it up off the ground. Reach-gated like every arm.
 */
public interface CampfireAccess {

    /** The four slots, empty ones included, and whether it burns — an unlit one cooks nothing. */
    record View(List<ItemStack> slots, boolean lit) {

        public int free() {
            int free = 0;
            for (ItemStack slot : slots) {
                if (slot.isEmpty()) {
                    free++;
                }
            }
            return free;
        }

        public boolean cooking() {
            return free() < slots.size();
        }
    }

    /** The campfire at {@code at}, or empty with none there or out of reach. */
    Optional<View> read(Pos at);

    /** Puts one of {@code stack} on a free slot; whether it went on. */
    boolean place(Pos at, ItemStack stack);

    /** A body with no arm for a campfire. */
    CampfireAccess NONE = new CampfireAccess() {
        @Override
        public Optional<View> read(Pos at) {
            return Optional.empty();
        }

        @Override
        public boolean place(Pos at, ItemStack stack) {
            return false;
        }
    };
}
