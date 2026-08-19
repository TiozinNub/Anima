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
    /**
     * Per-box ceiling on total item count; a cell with no entry is unlimited. What lets a test
     * script a genuine partial accept — {@code insert} of 5 into a box with room for 3 takes 3 and
     * reports 3, the shape {@code WorldContainers} already has and {@code PutItems} (Task 6) must
     * put the rest back rather than lose it.
     */
    public final Map<Pos, Integer> capacity = new LinkedHashMap<>();

    /** Every {@code open}, in order — a lid the tests can watch go up. */
    public final List<Pos> opened = new ArrayList<>();
    /** Every {@code close}, in order. Never opening and never closing is a pass, not a leak. */
    public final List<Pos> closed = new ArrayList<>();

    @Override
    public void open(Pos at) {
        opened.add(at);
    }

    @Override
    public void close(Pos at) {
        closed.add(at);
    }

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
        List<ItemStack> box = boxes.get(at);
        Integer cap = capacity.get(at);
        if (cap == null) {
            box.add(stack);
            return stack.count();
        }
        int held = box.stream().mapToInt(ItemStack::count).sum();
        int accepted = Math.min(stack.count(), Math.max(0, cap - held));
        if (accepted > 0) {
            box.add(stack.withCount(accepted));
        }
        return accepted;
    }

    @Override
    public ItemStack take(Pos at, ItemSpec spec, int max) {
        // A non-positive max must be a no-op, not a claim to take "everything up to a negative
        // number" — Math.min below would otherwise let the remainder rewrite grow the slot.
        if (max <= 0 || outOfReach.contains(at) || !boxes.containsKey(at)) {
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
