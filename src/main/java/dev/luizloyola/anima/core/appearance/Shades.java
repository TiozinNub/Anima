package dev.luizloyola.anima.core.appearance;

/**
 * The reserved colours a {@link ColorOp.Ramp}'s artwork is drawn in.
 *
 * <p>A ramped layer is a <em>shade-index map</em>: every pixel is transparent or one of these, and
 * the bake replaces each with the matching step of the ramp generated from the wearer's base
 * colour, so one authored PNG serves every skin tone, hair colour and dye.
 *
 * <p>Red 255, green 0, blue = the shade index: {@code #FF0000} is shade 0 (the deepest),
 * {@code #FF0001} shade 1 — mechanical, so a colour picker answers "which shade is this pixel".
 *
 * <p>⚠️ <b>A pixel that is neither transparent nor an index is left exactly as authored</b>, so a
 * stray colour shows up as itself rather than quietly becoming a shade it was never meant to be;
 * the editor's validation pass counts them (see {@link #indexOf}).
 */
public final class Shades {
    private Shades() {}

    /** Shade 0's colour; every other index adds to it. */
    public static final int FIRST = 0xFF0000;

    /** How many indices the encoding can express (the blue channel). */
    public static final int LIMIT = 256;

    public static int color(int index) {
        if (index < 0 || index >= LIMIT) {
            throw new IllegalArgumentException("shade index out of range: " + index);
        }
        return FIRST | index;
    }

    /** The shade this RGB stands for, or {@code -1} if it is not a reserved colour at all. */
    public static int indexOf(int rgb) {
        return (rgb & 0xFFFF00) == FIRST ? rgb & 0xFF : -1;
    }
}
