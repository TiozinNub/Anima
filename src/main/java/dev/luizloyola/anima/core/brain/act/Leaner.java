package dev.luizloyola.anima.core.brain.act;

/**
 * Crouch and creep to the edge of the cell the feet are in, so the eyes hang past it — the last
 * part of a cell of reach a body has, and one a plan can count on, because the body is put there
 * on purpose. The point is {@link #MAX_LEAN} from the cell's centre toward wherever it was asked
 * to lean, which keeps the body's box over the block: it hangs, and never falls. Releasing stands
 * the body up and walks it back to the middle. A body that cannot crouch is {@link #NONE}.
 */
public interface Leaner {

    /**
     * How far from the cell's centre the feet go. A body's box is 0.3 wide from its middle, and
     * it stays on the block while any of the box is over it: 0.8 would be the very edge, and this
     * leaves the last stride of the creep and a hand's breadth to spare.
     */
    double MAX_LEAN = 0.65;

    /**
     * Begin leaning toward {@code (x, z)} — anywhere, as far as the cell allows in that direction.
     * False when refused: mid-air, mid-lean, or asked to lean toward where the feet already are.
     */
    boolean toward(double x, double z);

    LeanState state();

    /** Stand up and go back to the middle of the cell; nothing to do when not leaning. */
    void release();

    Leaner NONE = new Leaner() {
        @Override
        public boolean toward(double x, double z) {
            return false;
        }

        @Override
        public LeanState state() {
            return LeanState.IDLE;
        }

        @Override
        public void release() {
        }
    };
}
