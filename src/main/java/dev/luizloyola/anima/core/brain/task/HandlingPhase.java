package dev.luizloyola.anima.core.brain.task;

/**
 * The three-beat shape a timed container transfer shares — open, settle, move — shared by
 * {@link TakeItems} and {@link PutItems} so a codec can decode it the same way it decodes any
 * other named enum ({@code Gait}): {@code valueOf} guarded into a {@code DataResult}, never
 * trusted raw off a hand-edited or future-renamed save.
 */
public enum HandlingPhase { OPEN, SETTLE, MOVE }
