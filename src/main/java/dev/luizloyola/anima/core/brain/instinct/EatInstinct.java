package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.brain.task.Task;

/**
 * The Eat drive — the canonical worked example from the brain design doc, as a layer-1 instinct.
 * Its {@code pressure} is the body's hunger ({@code 1 - food/20}, from {@link
 * dev.luizloyola.anima.core.agent.Metabolism#hunger()}), landing on the
 * {@code 0.30 / 0.60 / 0.85} bands {@link dev.luizloyola.anima.core.brain.ToleranceCurve} reads:
 * peckish waits for a task boundary, hungry preempts, starving lifts the cost cap. Its root is a
 * fresh {@link SatisfyHunger}, re-granted after each meal.
 */
public final class EatInstinct implements Instinct {

    @Override
    public double pressure(BrainContext ctx) {
        return ctx.percepts().metabolism().hunger();
    }

    @Override
    public Task root(BrainContext ctx) {
        return new SatisfyHunger();
    }

    @Override
    public String describe() {
        return "eat";
    }
}
