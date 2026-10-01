package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.FurnaceAccess;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Come back to a furnace: take out what it made of {@code output}, and with {@code fuel} given,
 * refuel it when what is left to smelt has nothing burning under it. Either may find nothing to do;
 * the furnace is read, not the prediction, since fuel can run out and a player can empty it.
 */
public final class UnloadFurnace implements CompoundTask {

    private final Pos at;
    private final ItemSpec output;
    private final @Nullable ItemSpec fuel;
    private final List<Method> methods = List.of(new Unload());

    public UnloadFurnace(Pos at, ItemSpec output) {
        this(at, output, null);
    }

    public UnloadFurnace(Pos at, ItemSpec output, @Nullable ItemSpec fuel) {
        this.at = at;
        this.output = output;
        this.fuel = fuel;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "take the " + output.name() + " out of the furnace";
    }

    public Pos at() {
        return at;
    }

    public ItemSpec output() {
        return output;
    }

    public @Nullable ItemSpec fuel() {
        return fuel;
    }

    private final class Unload implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return Workbench.distance(ctx.percepts().position(), at);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            List<Task> steps = new ArrayList<>(LoadFurnace.walkTo(ctx, at));
            steps.add(fuel == null ? new TendFurnace(at, output, null, 0, null, 0)
                    : new Try(new TendFurnace(at, output, null, 0, null, 0)));
            if (fuel != null) {
                steps.add(new Try(new Refuel(at, fuel)));
            }
            return steps;
        }

        @Override
        public String describe() {
            return "walk over and take it out";
        }
    }

    /** Fuel for what is left in a furnace with nothing burning, if it needs any. */
    public static final class Refuel implements CompoundTask {

        private final Pos at;
        private final ItemSpec fuel;
        private final List<Method> methods = List.of(new Fuel());

        public Refuel(Pos at, ItemSpec fuel) {
            this.at = at;
            this.fuel = fuel;
        }

        public Pos at() {
            return at;
        }

        public ItemSpec fuel() {
            return fuel;
        }

        @Override
        public List<Method> methods() {
            return methods;
        }

        @Override
        public String describe() {
            return "refuel the furnace";
        }

        /** How much fuel what is left needs: none while something burns or is waiting to. */
        private int needed(BrainContext ctx) {
            Optional<FurnaceAccess.View> view = ctx.actuators().furnaces().read(at);
            if (view.isEmpty() || view.get().lit() || !view.get().fuel().isEmpty()) {
                return 0;
            }
            ItemStack left = view.get().input();
            if (left.isEmpty()) {
                return 0;
            }
            return LoadFurnace.fuelFor(ctx, ItemSpec.anyOf(java.util.Set.of(left.id())), left.count(), fuel);
        }

        private final class Fuel implements Method {
            @Override
            public boolean applicable(BrainContext ctx) {
                return needed(ctx) > 0;
            }

            @Override
            public double estimateCost(BrainContext ctx) {
                return 0.0;
            }

            @Override
            public List<Task> decompose(BrainContext ctx) {
                int n = needed(ctx);
                List<Task> steps = new ArrayList<>();
                steps.add(new ObtainItem(fuel, n));
                steps.addAll(LoadFurnace.walkTo(ctx, at));
                steps.add(new TendFurnace(at, null, null, 0, fuel, n));
                return steps;
            }

            @Override
            public String describe() {
                return "get fuel and put it under";
            }
        }
    }
}
