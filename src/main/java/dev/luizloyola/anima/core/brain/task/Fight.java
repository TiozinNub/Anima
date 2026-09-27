package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;

/**
 * Kill somebody, as something to achieve. The fight-or-flight instinct uses it, and so does any plan
 * that needs something dead — a hunt first. It knows whom it fights and nothing about why.
 *
 * <p>One method today, with whatever the hand already holds. Choosing and drawing a weapon joins
 * as a step before {@link Engage} (combat spec, rung 4). The methods list is appended to and never
 * inserted into, since a saved plan resumes its method by index.
 */
public final class Fight implements CompoundTask {

    private final BeingId target;
    private final Pos where;
    private final List<Method> methods;

    /**
     * @param target whom to fight
     * @param where where they were when the fight was chosen
     */
    public Fight(BeingId target, Pos where) {
        this.target = target;
        this.where = where;
        this.methods = List.of(new Melee());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "fight " + target;
    }

    public BeingId target() {
        return target;
    }

    public Pos where() {
        return where;
    }

    /** Close in and hit with what is in hand. */
    private final class Melee implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new Engage(target, where));
        }

        @Override
        public String describe() {
            return "melee";
        }
    }
}
