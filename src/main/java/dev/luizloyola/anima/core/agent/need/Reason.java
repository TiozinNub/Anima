package dev.luizloyola.anima.core.agent.need;

import java.util.Objects;

/**
 * One line of a need's {@code because:} readout — why its number is what it is.
 *
 * <p><b>Keys, not sentences.</b> Core has no words: it says which line to print and what fills the
 * slot in it, and whoever is displaying it resolves both against the reader's own language. That is
 * what lets a consumer's need read like a sentence in every language without Anima knowing any of
 * them, and it is why the thing named ({@code effect.minecraft.strength}) is a key too rather than
 * a string resolved against whatever locale the server happens to run in.
 *
 * <p><b>The lang string decides its own arity.</b> Both the named thing and the amount are always
 * offered; a line that only wants one of them simply has one slot. {@code "Has %s applied to them"}
 * and {@code "%s is %s"} are both ordinary reasons.
 *
 * @param key what to print — {@code anima.needs.vigor.effect}
 * @param arg the lang key of what it names, or empty for a line that names nothing
 * @param amount what this term contributed to the need's value, signed; {@code 0} for a line that
 *     is not arithmetic
 */
public record Reason(String key, String arg, double amount) {

    public Reason {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(arg, "arg");
    }

    /** A line that names something and moves nothing — most of them. */
    public static Reason of(String key, String arg) {
        return new Reason(key, arg, 0.0);
    }
}
