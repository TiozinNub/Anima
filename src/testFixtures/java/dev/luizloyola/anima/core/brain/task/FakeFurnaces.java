package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.FurnaceAccess;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Furnaces a test puts down: three slots each, nothing smelts unless the test says so. */
public final class FakeFurnaces implements FurnaceAccess {

    /** A furnace's slots, mutable so a test can smelt by hand. */
    public static final class Box {
        public ItemStack input = ItemStack.EMPTY;
        public ItemStack fuel = ItemStack.EMPTY;
        public ItemStack output = ItemStack.EMPTY;
        public boolean lit;
    }

    public final Map<Pos, Box> boxes = new HashMap<>();

    public Box at(Pos at) {
        return boxes.computeIfAbsent(at, key -> new Box());
    }

    @Override
    public Optional<View> read(Pos at) {
        Box box = boxes.get(at);
        return box == null ? Optional.empty() : Optional.of(new View(box.input, box.fuel, box.output, box.lit));
    }

    @Override
    public int load(Pos at, Slot slot, ItemStack stack) {
        Box box = boxes.get(at);
        if (box == null || stack.isEmpty()) {
            return 0;
        }
        ItemStack there = slot == Slot.INPUT ? box.input : box.fuel;
        int moved;
        ItemStack after;
        if (there.isEmpty()) {
            moved = Math.min(stack.count(), stack.maxStackSize());
            after = stack.withCount(moved);
        } else if (there.canStackWith(stack)) {
            moved = Math.min(stack.count(), there.maxStackSize() - there.count());
            after = there.withCount(there.count() + moved);
        } else {
            return 0;
        }
        if (slot == Slot.INPUT) {
            box.input = after;
        } else {
            box.fuel = after;
        }
        return moved;
    }

    @Override
    public ItemStack takeOutput(Pos at, ItemSpec spec, int max) {
        Box box = boxes.get(at);
        if (box == null || box.output.isEmpty() || !spec.matches(box.output.id()) || max <= 0) {
            return ItemStack.EMPTY;
        }
        int taken = Math.min(max, box.output.count());
        ItemStack out = box.output.withCount(taken);
        box.output = box.output.count() > taken ? box.output.withCount(box.output.count() - taken) : ItemStack.EMPTY;
        return out;
    }
}
