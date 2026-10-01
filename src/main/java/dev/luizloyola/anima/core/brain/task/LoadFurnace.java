package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Furnace;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Load a furnace: get the fuel, then what is to be smelted, walk over and put both in. Fuel first,
 * because planks are made of the logs a charcoal load is made of. Enough fuel for the whole load,
 * counted by the shortest burn the fuel spec takes and the longest smelt the input does.
 */
public final class LoadFurnace implements CompoundTask {

    private final Pos at;
    private final ItemSpec input;
    private final int count;
    private final ItemSpec fuel;
    private final List<Method> methods = List.of(new Load());

    public LoadFurnace(Pos at, ItemSpec input, int count, ItemSpec fuel) {
        this.at = at;
        this.input = input;
        this.count = Math.max(1, Math.min(64, count));
        this.fuel = fuel;
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

    private final class Load implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return fuelFor(ctx, input, count, fuel) > 0;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return Workbench.distance(ctx.percepts().position(), at);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            int burning = fuelFor(ctx, input, count, fuel);
            List<Task> steps = new ArrayList<>();
            steps.add(new ObtainItem(fuel, burning));
            steps.add(new ObtainItem(input, count));
            steps.addAll(walkTo(ctx, at));
            steps.add(new TendFurnace(at, null, input, count, fuel, burning));
            return steps;
        }

        @Override
        public String describe() {
            return "fuel, then the load, then the furnace";
        }
    }
}
