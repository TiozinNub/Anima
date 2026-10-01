package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.CampfireAccess;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Campfires a test puts down: four slots each, lit, and nothing cooks unless the test says so. */
public final class FakeCampfires implements CampfireAccess {

    /** A campfire's slots, mutable so a test can cook by hand. */
    public static final class Fire {
        public final ItemStack[] slots = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
        public boolean lit = true;

        /** Empties every slot, as cooking does, and returns what was on it. */
        public List<ItemStack> cookAll() {
            List<ItemStack> off = new ArrayList<>();
            for (int i = 0; i < slots.length; i++) {
                if (!slots[i].isEmpty()) {
                    off.add(slots[i]);
                    slots[i] = ItemStack.EMPTY;
                }
            }
            return off;
        }
    }

    public final Map<Pos, Fire> fires = new HashMap<>();
    /** Positions the body cannot reach, so a read and a place there come back empty. */
    public final java.util.Set<Pos> outOfReach = new java.util.HashSet<>();

    public Fire at(Pos at) {
        return fires.computeIfAbsent(at, key -> new Fire());
    }

    @Override
    public Optional<View> read(Pos at) {
        Fire fire = fires.get(at);
        return fire == null || outOfReach.contains(at) ? Optional.empty()
                : Optional.of(new View(Arrays.asList(fire.slots.clone()), fire.lit));
    }

    @Override
    public boolean place(Pos at, ItemStack stack) {
        Fire fire = fires.get(at);
        if (fire == null || outOfReach.contains(at) || stack.isEmpty()) {
            return false;
        }
        for (int i = 0; i < fire.slots.length; i++) {
            if (fire.slots[i].isEmpty()) {
                fire.slots[i] = stack.withCount(1);
                return true;
            }
        }
        return false;
    }
}
