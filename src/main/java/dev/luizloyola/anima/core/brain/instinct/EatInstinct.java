package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.brain.task.Task;

/**
 * The Eat drive — the brain design doc's worked example, as a layer-1 instinct. Its
 * {@code pressure} is the body's hunger ({@code 1 - food/20}, from
 * {@link dev.luizloyola.anima.core.agent.Needs#hunger()}), so the arbiter's
 * {@link dev.luizloyola.anima.core.brain.ToleranceCurve} bands ({@code 0.30 / 0.60 / 0.85}) make a
 * peckish Person wait for a task boundary, a hungry one preempt, a starving one lift the cost cap.
 * Root: a fresh {@link SatisfyHunger}, re-granted after each meal.
 */
public final class EatInstinct implements Instinct {

    @Override
    public double pressure(BrainContext ctx) {
        return ctx.percepts().needs().hunger();
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
