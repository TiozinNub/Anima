package dev.luizloyola.anima.core.brain.task;

import java.util.List;

/**
 * Eat the ready food just fetched. A goal rather than a {@link ConsumeItem}, because which slot it
 * landed in is only known once the fetch is done.
 */
public final class EatCarried implements CompoundTask {

    private final List<Method> methods = List.of(new EatReadyFood());

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "eat what was fetched";
    }
}
