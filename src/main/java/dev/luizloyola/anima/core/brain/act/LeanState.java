package dev.luizloyola.anima.core.brain.act;

/** Where a lean is: under way, held at the edge, being let go of, or dead. */
public enum LeanState {
    IDLE,
    LEANING,
    LEANT,
    RELEASING,
    FAILED
}
