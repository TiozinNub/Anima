package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.Optional;

/**
 * Reaching into a furnace, slot by slot — what goes in to be smelted, what burns, and what came
 * out. The container port fills and empties by slot order, which would load logs as fuel and take
 * the fuel back out as output. Reach-gated like every arm.
 */
public interface FurnaceAccess {

    /** The two slots a body loads. */
    enum Slot {
        INPUT,
        FUEL
    }

    /** What a furnace holds and whether it burns, read off the block. */
    record View(ItemStack input, ItemStack fuel, ItemStack output, boolean lit) {
    }

    /** The furnace at {@code at}, or empty with none there or out of reach. */
    Optional<View> read(Pos at);

    /** Puts what fits into one slot; returns how many items it took. */
    int load(Pos at, Slot slot, ItemStack stack);

    /** Takes up to {@code max} of what came out, if it matches {@code spec}. */
    ItemStack takeOutput(Pos at, ItemSpec spec, int max);

    /** A body with no arm for a furnace. */
    FurnaceAccess NONE = new FurnaceAccess() {
        @Override
        public Optional<View> read(Pos at) {
            return Optional.empty();
        }

        @Override
        public int load(Pos at, Slot slot, ItemStack stack) {
            return 0;
        }

        @Override
        public ItemStack takeOutput(Pos at, ItemSpec spec, int max) {
            return ItemStack.EMPTY;
        }
    };
}
