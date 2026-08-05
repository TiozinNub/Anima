package dev.luizloyola.anima.core.appearance;

import java.util.List;

/**
 * The one true spelling of a {@link Recipe}, and the hash taken over it.
 *
 * <p>A baked appearance is cached and named by its recipe's hash, so a texture id stands in for a
 * look anywhere an id is accepted. That needs the spelling <b>canonical</b> (one recipe, one
 * string) and <b>injective</b> (two recipes, two strings).
 *
 * <h2>The grammar</h2>
 * One line. No whitespace, ever:
 * <pre>
 * |minecraft:entity/person/body_a@0,0,64,64:ramp(c68642,skin)|autarkia:hair/long@40,8,8,8:pal(3a2a1a-6b4423),hsv(-40,1050,980)
 * </pre>
 * <ul>
 *   <li>{@code |} <b>prefixes</b> every part, so parts are self-delimiting records;</li>
 *   <li>{@code @x,y,w,h} is the placement, {@code :} separates the op list, and ops are
 *       comma-separated <b>in application order</b> — they do not commute;</li>
 *   <li>the colon is always present, even with no ops.</li>
 * </ul>
 *
 * <p><b>Injectivity comes free from Minecraft's own id charset</b>: {@code [a-z0-9_.-/]} and a
 * namespace separator, so no texture id contains {@code |}, {@code @}, {@code :}, {@code ,} or a
 * parenthesis, and every other field is an integer or a fixed op name. Only the hash's own 64-bit
 * collisions remain.
 *
 * <h2>The float rule</h2>
 * <b>Every float is quantised to fixed point before it enters the stream</b> — hue in tenths of a
 * degree, saturation and value in thousandths. Never {@code Float.toString}: JDK 19 made it
 * shortest-representation, so one recipe would name two textures on two nodes.
 *
 * <h2>No format version</h2>
 * No {@code v1|} prefix (decision: Luiz): the cache is in memory only, so changing this grammar
 * takes a rebuild, which empties it.
 */
public final class Canonical {
    private Canonical() {}

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * The canonical stream for an ordered list of parts. A plain string because a human reads it
     * out of a log line when two Persons unexpectedly share, or do not share, a texture.
     */
    public static String stream(List<Part> parts) {
        StringBuilder out = new StringBuilder(64);
        for (Part part : parts) {
            out.append('|').append(part.texture())
                    .append('@').append(part.x()).append(',').append(part.y())
                    .append(',').append(part.w()).append(',').append(part.h())
                    .append(':');
            List<ColorOp> ops = part.ops();
            for (int i = 0; i < ops.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                appendOp(out, ops.get(i));
            }
        }
        return out.toString();
    }

    /**
     * FNV-1a over the stream's ASCII bytes. The grammar is ASCII by construction, so a char is a
     * byte and no encoder is involved.
     */
    public static long hash(String stream) {
        long hash = FNV_OFFSET;
        for (int i = 0; i < stream.length(); i++) {
            hash ^= stream.charAt(i) & 0xFF;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /** The hash's texture-id form: unsigned, lower-case, zero-padded, always sixteen characters. */
    public static String hex(long hash) {
        return String.format("%016x", hash);
    }

    private static void appendOp(StringBuilder out, ColorOp op) {
        // An if/else ladder because core compiles at Java 17 language level, where switch patterns
        // are unavailable. The trailing throw stands in for exhaustiveness: a new ColorOp with no
        // spelling here fails loudly instead of hashing to its neighbour's texture.
        if (op instanceof ColorOp.Multiply multiply) {
            out.append("mul(").append(hex6(multiply.rgb())).append(')');
        } else if (op instanceof ColorOp.Hsv hsv) {
            out.append("hsv(").append(tenths(hsv.hueDegrees()))
                    .append(',').append(thousandths(hsv.satMul()))
                    .append(',').append(thousandths(hsv.valMul())).append(')');
        } else if (op instanceof ColorOp.Palette palette) {
            out.append("pal(");
            List<ColorOp.Swap> swaps = palette.swaps();
            for (int i = 0; i < swaps.size(); i++) {
                if (i > 0) {
                    out.append(';');
                }
                out.append(hex6(swaps.get(i).fromRgb())).append('-').append(hex6(swaps.get(i).toRgb()));
            }
            out.append(')');
        } else if (op instanceof ColorOp.Ramp ramp) {
            // By NAME, not by the shades behind it — see the note on RampSpec about why that makes
            // clearing the bake cache on a catalog reload an invariant rather than a nicety.
            out.append("ramp(").append(hex6(ramp.baseRgb())).append(',').append(ramp.spec().name()).append(')');
        } else {
            throw new IllegalArgumentException("no canonical spelling for colour operation: " + op);
        }
    }

    private static String hex6(int rgb) {
        return String.format("%06x", rgb & 0xFFFFFF);
    }

    private static int tenths(float value) {
        return Math.round(value * 10.0F);
    }

    private static int thousandths(float value) {
        return Math.round(value * 1000.0F);
    }
}
