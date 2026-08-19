package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.ContainerAccess;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A scripted container the tests can fill, empty and cap. */
public final class FakeContainers implements ContainerAccess {

    /** Cells the test has put a container at, with what is in them. */
    public final Map<Pos, List<ItemStack>> boxes = new LinkedHashMap<>();
    /** Cells that refuse everything, however empty they look — a full chest. */
    public final java.util.Set<Pos> full = new java.util.LinkedHashSet<>();
    /** Cells beyond the arm. */
    public final java.util.Set<Pos> outOfReach = new java.util.LinkedHashSet<>();

    @Override
    public Optional<List<ItemStack>> contents(Pos at) {
        if (outOfReach.contains(at) || !boxes.containsKey(at)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(boxes.get(at)));
    }

    @Override
    public int insert(Pos at, ItemStack stack) {
        if (outOfReach.contains(at) || !boxes.containsKey(at) || full.contains(at)) {
            return 0;
        }
        boxes.get(at).add(stack);
        return stack.count();
    }

    @Override
    public ItemStack take(Pos at, ItemSpec spec, int max) {
        if (outOfReach.contains(at) || !boxes.containsKey(at)) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> box = boxes.get(at);
        for (int i = 0; i < box.size(); i++) {
            ItemStack held = box.get(i);
            if (!spec.matches(held.id())) {
                continue;
            }
            int taken = Math.min(max, held.count());
            if (taken == held.count()) {
                box.remove(i);
            } else {
                box.set(i, held.withCount(held.count() - taken));
            }
            return held.withCount(taken);
        }
        return ItemStack.EMPTY;
    }
}
