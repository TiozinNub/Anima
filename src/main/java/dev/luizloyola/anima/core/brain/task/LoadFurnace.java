package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Furnace;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Load a furnace: walk to it, get the fuel, then what is to be smelted, and put both in. The walk
 * comes first because a furnace stands at a base, beside its stores: planned from where the job was
 * taken, a load once came from trees 133 blocks out with 218 logs in the yard (2026-10-01). Fuel
 * before the load, because planks are made of the logs a charcoal load is made of. Enough fuel for
 * the whole load, counted by the shortest burn the fuel spec takes and the longest smelt the input
 * does.
 */
public final class LoadFurnace implements CompoundTask {

    private final Pos at;
    private final ItemSpec input;
    private final int count;
    private final ItemSpec fuel;
    private final @Nullable ItemSpec kindling;
    // Burn appended after Load: a saved plan resumes its method by index.
    private final List<Method> methods = List.of(new Load(), new Burn());

    public LoadFurnace(Pos at, ItemSpec input, int count, ItemSpec fuel) {
        this(at, input, count, fuel, null);
    }

    /**
     * @param kindling what to burn before {@code fuel} when enough of one kind is held or stored —
     *                 litter swept up while clearing rather than planks cut from the load's own logs
     */
    public LoadFurnace(Pos at, ItemSpec input, int count, ItemSpec fuel, @Nullable ItemSpec kindling) {
        this.at = at;
        this.input = input;
        this.count = Math.max(1, Math.min(64, count));
        this.fuel = fuel;
        this.kindling = kindling;
    }

    /** How much of {@code fuel} burns long enough to smelt {@code count} of {@code input}; 0 if it cannot. */
    public static int fuelFor(BrainContext ctx, ItemSpec input, int count, ItemSpec fuel) {
        int smelt = ctx.percepts().smelting().smeltTicks(input);
        int burn = ctx.percepts().smelting().burnTicks(fuel);
        if (smelt <= 0 || burn <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) smelt * count / burn);
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "load the furnace with " + count + " " + input.name();
    }

    public Pos at() {
        return at;
    }

    public ItemSpec input() {
        return input;
    }

    public int count() {
        return count;
    }

    public ItemSpec fuel() {
        return fuel;
    }

    public Optional<ItemSpec> kindling() {
        return Optional.ofNullable(kindling);
    }

    /**
     * The one kind of kindling to burn — a fuel slot holds one — the most of any the pack and the
     * party's stores were seen holding, if that covers the whole load. Null when none does.
     */
    @Nullable String kindlingFor(BrainContext ctx) {
        if (kindling == null) {
            return null;
        }
        Map<String, Integer> have = new HashMap<>();
        for (Inventory.Entry entry : ctx.percepts().inventory().occupied()) {
            if (kindling.matches(entry.stack().id())) {
                have.merge(entry.stack().id(), entry.stack().count(), Integer::sum);
            }
        }
        long now = ctx.percepts().time();
        for (PoiMemory store : Store.ours(ctx)) {
            if (ctx.knowledge().isAvoided(Store.POI, store.anchor(), now)) {
                continue;
            }
            ctx.knowledge().insideOf(store.anchor()).ifPresent(seen -> {
                for (ItemStack stack : seen.stacks()) {
                    if (kindling.matches(stack.id())) {
                        have.merge(stack.id(), stack.count(), Integer::sum);
                    }
                }
            });
        }
        String best = null;
        for (Map.Entry<String, Integer> kind : have.entrySet()) {
            int needed = fuelFor(ctx, input, count, ItemSpec.anyOf(Set.of(kind.getKey())));
            if (needed > 0 && kind.getValue() >= needed && (best == null || kind.getValue() > have.get(best))) {
                best = kind.getKey();
            }
        }
        return best;
    }

    /**
     * Walk to stand within reach of a furnace, or nothing when already there. A block short of the
     * reach: the arm measures from the eyes to the block's centre, which is further than feet to
     * cell — 4.15 against 4 at four blocks along the ground.
     */
    static List<Task> walkTo(BrainContext ctx, Pos at) {
        if (Workbench.distance(ctx.percepts().position(), at) <= Furnace.REACH - 1.0) {
            return List.of();
        }
        Pos beside = EnsureTable.WalkToKnown.standableBeside(at, ctx);
        return List.of(new GoTo(beside.x(), beside.y(), beside.z()));
    }

    /**
     * The walk back after getting things near a furnace: always there, since what the getting
     * moves is only known once it has run, and a body already beside it arrives at once.
     */
    static Task backBeside(BrainContext ctx, Pos at) {
        Pos beside = EnsureTable.WalkToKnown.standableBeside(at, ctx);
        return new GoTo(beside.x(), beside.y(), beside.z());
    }

    private final class Load implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return fuelFor(ctx, input, count, fuel) > 0;
        }

        /** A job that comes to a furnace already chose the walk; the 1 only puts {@link Burn} first. */
        @Override
        public double estimateCost(BrainContext ctx) {
            return 1.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            int burning = fuelFor(ctx, input, count, fuel);
            List<Task> steps = new ArrayList<>(walkTo(ctx, at));
            steps.add(new ObtainItem(fuel, burning));
            steps.add(new ObtainItem(input, count));
            steps.add(backBeside(ctx, at));
            steps.add(new TendFurnace(at, null, input, count, fuel, burning));
            return steps;
        }

        @Override
        public String describe() {
            return "fuel, then the load, then the furnace";
        }
    }

    /** {@link Load} with kindling for the fuel, when there is enough of one kind for the load. */
    private final class Burn implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return kindlingFor(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            ItemSpec burn = ItemSpec.anyOf(Set.of(java.util.Objects.requireNonNull(kindlingFor(ctx))));
            int burning = fuelFor(ctx, input, count, burn);
            List<Task> steps = new ArrayList<>(walkTo(ctx, at));
            steps.add(new ObtainItem(burn, burning));
            steps.add(new ObtainItem(input, count));
            steps.add(backBeside(ctx, at));
            steps.add(new TendFurnace(at, null, input, count, burn, burning));
            return steps;
        }

        @Override
        public String describe() {
            return "kindling, then the load, then the furnace";
        }
    }
}
