package dev.luizloyola.anima.core.appearance;

/**
 * The <b>default</b> reserved colours a {@link ColorOp.Ramp}'s artwork is drawn in, used by any
 * ramp that declares no {@link RampSpec#keys() keys} of its own.
 *
 * <p>A ramped layer is a <em>shade-index map</em>, not a picture: every pixel is transparent or one
 * of these, and the bake replaces each with the matching step of the ramp generated from the
 * wearer's base colour, so one PNG serves every skin tone, hair colour and dye.
 *
 * <p>The encoding is red 255, green 0, blue = the shade index: {@code #FF0000} is shade 0 (the
 * deepest), {@code #FF0001} shade 1. No artist reaches for these by accident, so a shade map
 * announces itself as one.
 *
 * <p>⚠️ <b>It is close to unpaintable</b> — consecutive indices are identical-looking red, readable
 * only with a colour picker. Art authored from scratch can be drawn in it and checked in the
 * editor; art adapted from a reference should keep its own palette and declare those colours as
 * {@link RampSpec} keys.
 *
 * <p>⚠️ <b>A pixel that is neither transparent nor a key of the ramp being applied is left exactly
 * as authored</b>, so a stray colour survives as itself. The editor's validation pass counts them;
 * see {@link RampSpec#isKey}.
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
