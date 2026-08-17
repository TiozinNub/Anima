package dev.luizloyola.anima.core.agent.need;

import java.util.Locale;
import java.util.Objects;

/**
 * What a need does to behaviour — the answer to "what does Vigor do?", read from the registry
 * instead of by reading {@code FleeInstinct}.
 *
 * <p><b>Two verbs, because one is not enough</b> (decision: Luiz, 2026-08-06). A {@link Verb#DRIVE}
 * proposes something to do and bids the need's pressure to do it; a {@link Verb#MODULATE} proposes
 * nothing and never wins an argument, it changes how another one resolves. A contract shaped as "a
 * need is an instinct's pressure" cannot express the second, and the temptation would be to
 * hand-wire it inside whichever drive it weighs, where nothing could list it.
 *
 * <p><b>Declared here, supplied elsewhere.</b> This names the binding; the task a drive proposes is
 * the brain's business ({@code NeedDrive}), which is what keeps {@code core/agent} from having to
 * know what a {@code Task} is. A binding with nothing supplying it yet is a declaration of intent,
 * and reads as one.
 */
public final class Binding {

    /** What a need does to a decision. */
    public enum Verb {
        /** Proposes a task, and bids the need's pressure for it. */
        DRIVE,
        /** Proposes nothing; weighs in on a decision something else is making. */
        MODULATE
    }

    /**
     * Which end of a need this binding answers. A two-sided need presses at both ends and the two
     * ends have opposite errands — lonely and crowded are the same pressure with nothing in common.
     */
    public enum Side {
        /** Below the comfortable stretch: hungry, lonely. */
        BELOW,
        /** Above it: crowded. */
        ABOVE,
        /** Either — a need with one restful end, or a modulator with no side to take. */
        EITHER;

        /** Whether {@link Ramp#side} answering {@code -1 / 0 / +1} means this end is the one asking. */
        public boolean pressing(int rampSide) {
            return switch (this) {
                case BELOW -> rampSide < 0;
                case ABOVE -> rampSide > 0;
                case EITHER -> rampSide != 0;
            };
        }
    }

    private final String needKey;
    private final Verb verb;
    private final Side side;
    private final String key;

    /**
     * The need itself, which does not exist yet while its own builder is collecting this — see
     * {@link #attach}. Not final, and written exactly once.
     */
    private NeedKind need;

    Binding(String needKey, Verb verb, Side side, String key) {
        this.needKey = Objects.requireNonNull(needKey, "needKey");
        this.verb = Objects.requireNonNull(verb, "verb");
        this.side = Objects.requireNonNull(side, "side");
        this.key = Objects.requireNonNull(key, "key");
    }

    /**
     * Points this at the finished need, from that need's own constructor. A binding is declared
     * while its need is still being built, so it cannot be handed the real thing at the time — and
     * being handed the half-built stand-in instead is worse than nothing: it has no levels and no
     * ramp, so every drive reading through it would find a need that wants nothing.
     */
    void attach(NeedKind owner) {
        this.need = owner;
    }

    /** The need this binding belongs to. */
    public NeedKind need() {
        return need;
    }

    /** Whether this proposes something or only weighs in. */
    public Verb verb() {
        return verb;
    }

    /** Which end of the need is asking. */
    public Side side() {
        return side;
    }

    /**
     * Stable id — {@code eat}, {@code seek_people}. A drive's {@code Instinct.key()}, so it is also
     * what a saved fail-cooldown is filed under: renaming one forgives that cooldown once.
     */
    public String key() {
        return key;
    }

    /** Whether the body is currently on the end of the need this binding answers. */
    public boolean pressing(int rampSide) {
        return side.pressing(rampSide);
    }

    /** {@code hunger drives eat (below)} — one line for a registry listing. */
    public String describe() {
        return String.format(Locale.ROOT, "%s %s %s (%s)", needKey,
                verb == Verb.DRIVE ? "drives" : "modulates", key,
                side.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return key;
    }
}
