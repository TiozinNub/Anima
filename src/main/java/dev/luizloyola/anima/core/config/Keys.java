package dev.luizloyola.anima.core.config;

import java.security.SecureRandom;

/**
 * The generator behind {@link KnobSpec.Kind#KEY}: a secret an installation makes for itself the
 * first time it needs one, then keeps.
 *
 * <p><b>Alphanumeric on purpose.</b> A key ends up in a URL, in a chat line somebody clicks, and in
 * a hand-edited TOML file — so nothing in it may need escaping, quoting or a second look. That
 * rules out the punctuation a general-purpose token would carry and costs about six bits against a
 * base64 alphabet of the same length, which {@link #LENGTH} pays for several times over.
 *
 * <p>{@link SecureRandom}, not {@code Random}: this is the only thing guarding the web debugger,
 * and a seeded PRNG's output is predictable from one sample.
 */
public final class Keys {

    /** Characters a generated key may contain — no punctuation, nothing that needs escaping. */
    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * How many characters a freshly generated key gets. 16 over a 62-character alphabet is about
     * 95 bits — far past guessing, and still short enough to read off a screen and retype.
     */
    public static final int LENGTH = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Keys() {
    }

    /** A fresh key. Never empty, so it is always distinguishable from "not generated yet". */
    public static String generate() {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /** Whether every character is one {@link #generate} could have produced. */
    public static boolean wellFormed(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (ALPHABET.indexOf(key.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code config} with every ungenerated {@link KnobSpec.Kind#KEY} filled in, or the same
     * instance when there was nothing to do.
     *
     * <p>Returning the original unchanged is what lets the caller decide whether the file needs
     * writing — generating unconditionally would rewrite the config on every single load.
     */
    public static ConfigValues materialise(ConfigValues config) {
        ConfigValues out = config;
        for (KnobSpec knob : config.set().knobs()) {
            if (knob.kind() == KnobSpec.Kind.KEY && out.s(knob).isEmpty()) {
                out = out.with(knob, generate());
            }
        }
        return out;
    }
}
