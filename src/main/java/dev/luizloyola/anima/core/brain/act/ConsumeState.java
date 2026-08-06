package dev.luizloyola.anima.core.brain.act;

/**
 * Where the gullet is in its lifecycle — the exact shape of {@link MoveState} for the consume
 * domain. Read by {@code ConsumeItem} on the ticks after a successful {@link ItemConsumer#begin};
 * written by the mod-layer consumer as the vanilla item-use advances.
 */
public enum ConsumeState {
    /** No consumption in progress — nothing was begun, or {@link ItemConsumer#abort} ended it. */
    IDLE,
    /** Mid-chew: the item-use is running (vanilla's ~32-tick eat, with animation and sound). */
    CONSUMING,
    /**
     * The last consumption completed and the body applied the nutrition ({@code Metabolism.eat}) and
     * decremented the stack — the task observing this has nothing left to do but report success.
     */
    FINISHED,
    /** The last consumption died: the item stopped being consumable mid-chew, or the use was
     *  interrupted by something that was not {@link ItemConsumer#abort}. */
    FAILED
}
