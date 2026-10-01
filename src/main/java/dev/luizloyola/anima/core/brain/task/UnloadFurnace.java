package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;

/** Walk to a furnace and take out what it made of {@code output}. */
public final class UnloadFurnace implements CompoundTask {

    private final Pos at;
    private final ItemSpec output;
    private final List<Method> methods = List.of(new Unload());

    public UnloadFurnace(Pos at, ItemSpec output) {
        this.at = at;
        this.output = output;
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
            steps.add(new TendFurnace(at, output, null, 0, null, 0));
            return steps;
        }

        @Override
        public String describe() {
            return "walk over and take it out";
        }
    }
}
