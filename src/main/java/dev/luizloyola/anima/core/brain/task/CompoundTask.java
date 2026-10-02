package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.List;
import java.util.Optional;

/**
 * A goal: something to achieve, not something to do. A compound never touches actuators — it
 * only names its alternatives ({@link #methods()}); the {@link TaskExecutor} picks the cheapest
 * applicable one on reaching this node (lazy expansion) and falls back to the next when a choice
 * fails (the one failure rule). See the brain design doc's task-machinery section.
 *
 * <p>The methods LIST is the extension point: {@code SatisfyHunger} gains containers, harvesting
 * and hunting by ADDING methods here, leaving the compound, the executor and every existing
 * method untouched — behavior grows combinatorially, code linearly.
 */
public non-sealed interface CompoundTask extends Task {
    /**
     * The fixed alternatives for achieving this goal. Fixed means the LIST does not depend on the
     * world — which entries are usable right now ({@link Method#applicable}) and what each would
     * cost ({@link Method#estimateCost}) are decided at expansion time, against fresh percepts.
     */
    List<Method> methods();

    /**
     * Why this goal may not be pursued at all, asked before any method is. Empty by default;
     * {@link ObtainItem} answers for the gate, so a body forbidden an item goes after it by no way.
     */
    default Optional<String> refusal(BrainContext ctx) {
        return Optional.empty();
    }

    /**
     * One-line goal summary for the debug readout, e.g. {@code "satisfy hunger"} — chained by
     * {@link TaskExecutor#describe()} into the expansion path.
     */
    String describe();

    /**
     * Whether this compound is a failed primitive's {@link PrimitiveTask#standIn}. Nothing under
     * one gets a stand-in of its own.
     */
    default boolean standsIn() {
        return false;
    }

    /**
     * Puts the task this compound was built around back into its restored decomposition, when the
     * decomposition hands that same object on — a wrapper's child. A restore decodes the two apart;
     * this makes them one again, so what the wrapper reads is what runs. Nothing by default.
     */
    default void rejoin(List<Task> subtasks) {
    }
}
