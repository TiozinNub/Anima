package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Lying in a bed, as a player or a villager does: the body on the bed, the bed marked taken, until
 * it gets up or something wakes it — a hit, the bed broken.
 */
public interface Bed {

    /** A body that never lies down. */
    Bed NONE = new Bed() {
        @Override
        public boolean lieDown(Pos bed) {
            return false;
        }

        @Override
        public boolean asleep() {
            return false;
        }

        @Override
        public void getUp() {
        }
    };

    /**
     * Lie down in the bed one of whose halves is {@code bed}. True when the body is asleep in it
     * afterwards, already being so included; false with no bed there, out of reach, or someone in it.
     */
    boolean lieDown(Pos bed);

    /** Whether the body is lying in a bed now — woken by anything, it is not. */
    boolean asleep();

    /** Get up, if lying down. */
    void getUp();
}
