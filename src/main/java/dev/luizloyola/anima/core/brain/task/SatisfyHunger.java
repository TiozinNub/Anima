package dev.luizloyola.anima.core.brain.task;

import java.util.List;

/**
 * "They are hungry" as something to ACHIEVE, with no opinion about how: {@link EatReadyFood}
 * (free), {@link EatLastResort} (desperation-priced) and {@link EatFromStore} (priced by the walk).
 * Cheapest-wins prefers ready food when any is in hand, and the arbiter's cost tolerance decides
 * what hunger can afford. The methods list is the extension point ({@link CompoundTask}), appended
 * to and never inserted into, since a saved plan resumes its method by index; all methods failing
 * or priced out bubbles a root FAILED.
 */
public final class SatisfyHunger implements CompoundTask {
    private final List<Method> methods = List.of(new EatReadyFood(), new EatLastResort(),
            new EatFromStore());

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "satisfy hunger";
    }
}
