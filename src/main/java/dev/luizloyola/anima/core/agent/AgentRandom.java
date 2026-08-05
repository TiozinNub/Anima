package dev.luizloyola.anima.core.agent;

import java.util.random.RandomGenerator;

/**
 * One agent's stream of chance, in a form that can be written down.
 *
 * <p><b>Why not {@code java.util.Random}.</b> A brain's randomness picks where a wander beat roams
 * to and which way a frightened body scatters, so a stream that restarts when the server does is a
 * reboot the agent noticed — and neither {@code Random} nor the standard {@code RandomGenerator}
 * factories will tell you their state, so neither can be saved and resumed.
 *
 * <p>Its whole state is a single {@code long}: SplitMix64, Steele, Lea and Flood's mixer, the one
 * the JDK uses to seed its own generators. No bad seeds — every 64-bit value is a legal state, so a
 * corrupt or hand-edited save cannot produce a degenerate stream. Implementing
 * {@link RandomGenerator} derives every other method from {@link #nextLong()} alone.
 *
 * <p>Core-pure, Java 17. The body seeds it once from the entity's own randomness and thereafter
 * saves and restores {@link #state()}.
 */
public final class AgentRandom implements RandomGenerator {

    /** The golden-ratio odd constant SplitMix64 walks the state by. */
    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    public AgentRandom(long seed) {
        this.state = seed;
    }

    /** The whole of this generator's state — save this, and the stream resumes exactly. */
    public long state() {
        return state;
    }

    public void restore(long state) {
        this.state = state;
    }

    @Override
    public long nextLong() {
        long z = (state += GAMMA);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
