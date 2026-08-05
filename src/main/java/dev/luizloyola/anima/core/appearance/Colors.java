package dev.luizloyola.anima.core.appearance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The colour arithmetic behind {@link ColorOp}.
 *
 * <p>Ops are <b>compiled once per part</b>, not interpreted per pixel: a {@link ColorOp.Palette} or
 * {@link ColorOp.Ramp} becomes a lookup table, so a 64×64 part is 4096 map reads rather than 4096
 * colour-space conversions.
 *
 * <p><b>Alpha is never touched and never created.</b> Every op transforms RGB and passes alpha
 * through, and a fully transparent pixel is skipped — otherwise a recolour drags a sprite's dead
 * space into visibility, which at 64×64 is a halo around everything.
 */
public final class Colors {
    private Colors() {}

    /**
     * Fold an op list into one pixel function, applied left to right.
     *
     * <p>An {@code instanceof} ladder because {@code core} compiles at Java 17 language level. The
     * trailing throw stands in for exhaustiveness: a new op that forgets to compile here fails on
     * its first bake rather than silently doing nothing.
     */
    public static IntUnaryOperator compile(List<ColorOp> ops) {
        return compile(ops, null);
    }

    /**
     * As {@link #compile(List)}, but able to measure from the art.
     *
     * <p>{@link ColorOp.Retint} takes its reference from the sprite it is about to recolour, so it
     * is the one operation that cannot be folded without the pixels in hand.
     */
    public static IntUnaryOperator compile(List<ColorOp> ops, @Nullable Sprite sprite) {
        IntUnaryOperator compiled = argb -> argb;
        for (ColorOp op : ops) {
            IntUnaryOperator step = compileOne(op, sprite);
            IntUnaryOperator previous = compiled;
            compiled = argb -> step.applyAsInt(previous.applyAsInt(argb));
        }
        return compiled;
    }

    private static IntUnaryOperator compileOne(ColorOp op, @Nullable Sprite sprite) {
        if (op instanceof ColorOp.Multiply multiply) {
            int rgb = multiply.rgb();
            return argb -> multiply(argb, rgb);
        }
        if (op instanceof ColorOp.Hsv hsv) {
            return argb -> shift(argb, hsv.hueDegrees(), hsv.satMul(), hsv.valMul());
        }
        if (op instanceof ColorOp.Palette palette) {
            Map<Integer, Integer> lookup = new HashMap<>();
            for (ColorOp.Swap swap : palette.swaps()) {
                lookup.putIfAbsent(swap.fromRgb() & 0xFFFFFF, swap.toRgb() & 0xFFFFFF);
            }
            return argb -> replace(argb, lookup);
        }
        if (op instanceof ColorOp.Retint retint) {
            int reference = sprite == null ? retint.toRgb() : dominant(sprite);
            float[] from = toHsv(reference);
            float[] target = toHsv(retint.toRgb());
            float hue = target[0] - from[0];
            float saturation = from[1] < 1e-4F ? 1.0F : target[1] / from[1];
            float value = from[2] < 1e-4F ? 1.0F : target[2] / from[2];
            return argb -> shift(argb, hue, saturation, value);
        }
        if (op instanceof ColorOp.Ramp ramp) {
            RampSpec spec = ramp.spec();
            int[] shades = ramp(ramp.baseRgb(), spec);
            Map<Integer, Integer> lookup = new HashMap<>();
            for (int index = 0; index < shades.length; index++) {
                // The key is the ramp's, not Shades' — a ramp may be drawn in the reference art's
                // own colours rather than the reserved encoding. See RampSpec.
                lookup.put(spec.keyAt(index), shades[index] & 0xFFFFFF);
            }
            return argb -> replace(argb, lookup);
        }
        throw new IllegalArgumentException("no colour arithmetic for operation: " + op);
    }

    /**
     * The shades a ramp generates from one base colour — what the editor draws as a strip beside
     * the base swatch.
     */
    public static int[] ramp(int baseRgb, RampSpec spec) {
        float[] hsv = toHsv(baseRgb);
        int[] shades = new int[spec.steps()];
        for (int index = 0; index < shades.length; index++) {
            RampSpec.Shade shade = spec.shades().get(index);
            float hue = wrapHue(hsv[0] + shade.hueShift10() / 10.0F);
            float value = clamp(hsv[2] * (shade.valMul1000() / 1000.0F));
            float saturation = clamp(hsv[1] * (shade.satMul1000() / 1000.0F));
            shades[index] = toRgb(hue, saturation, value);
        }
        return shades;
    }

    /**
     * The most common fully opaque colour in a sprite — what a {@link ColorOp.Retint} measures from.
     *
     * <p>Fully opaque only: partial alpha is shading drawn over whatever is beneath, not the
     * material itself, and counting it would drag the reference toward a colour the layer is not
     * made of.
     */
    public static int dominant(Sprite sprite) {
        Map<Integer, Integer> counts = new HashMap<>();
        int best = 0;
        int bestCount = 0;
        for (int pixel : sprite.argb()) {
            if ((pixel >>> 24) != 0xFF) {
                continue;
            }
            int rgb = pixel & 0xFFFFFF;
            int count = counts.merge(rgb, 1, Integer::sum);
            if (count > bestCount) {
                bestCount = count;
                best = rgb;
            }
        }
        return best;
    }

    /** Grey master × colour, per channel. Darkens only. That is the whole reason ramps exist. */
    public static int multiply(int argb, int rgb) {
        int red = ((argb >> 16 & 0xFF) * (rgb >> 16 & 0xFF)) / 255;
        int green = ((argb >> 8 & 0xFF) * (rgb >> 8 & 0xFF)) / 255;
        int blue = ((argb & 0xFF) * (rgb & 0xFF)) / 255;
        return (argb & 0xFF000000) | red << 16 | green << 8 | blue;
    }

    /**
     * Rotate hue and scale saturation and value.
     *
     * <p>⚠️ Value is clamped <em>before</em> saturation is applied. Scaling an already-out-of-range
     * value and clamping afterwards folds the two together and flips hue at both ends of a ramp,
     * which reads as a stray coloured pixel in the deepest shadow and nowhere else.
     */
    public static int shift(int argb, float hueDegrees, float satMul, float valMul) {
        float[] hsv = toHsv(argb);
        float value = clamp(hsv[2] * valMul);
        float saturation = clamp(hsv[1] * satMul);
        return (argb & 0xFF000000) | (toRgb(wrapHue(hsv[0] + hueDegrees), saturation, value) & 0xFFFFFF);
    }

    private static int replace(int argb, Map<Integer, Integer> lookup) {
        Integer replacement = lookup.get(argb & 0xFFFFFF);
        // Not in the table: leave it exactly as authored. A stray colour must stay visible as
        // itself rather than become a shade nobody chose — see Shades.
        return replacement == null ? argb : (argb & 0xFF000000) | replacement;
    }

    /** RGB to hue (degrees), saturation and value (both 0..1). */
    public static float[] toHsv(int rgb) {
        float red = (rgb >> 16 & 0xFF) / 255.0F;
        float green = (rgb >> 8 & 0xFF) / 255.0F;
        float blue = (rgb & 0xFF) / 255.0F;
        float max = Math.max(red, Math.max(green, blue));
        float min = Math.min(red, Math.min(green, blue));
        float span = max - min;
        float hue;
        if (span == 0.0F) {
            hue = 0.0F;
        } else if (max == red) {
            hue = 60.0F * (((green - blue) / span) % 6.0F);
        } else if (max == green) {
            hue = 60.0F * ((blue - red) / span + 2.0F);
        } else {
            hue = 60.0F * ((red - green) / span + 4.0F);
        }
        return new float[] {wrapHue(hue), max == 0.0F ? 0.0F : span / max, max};
    }

    /** Hue (degrees), saturation and value (both 0..1) back to a 24-bit RGB. */
    public static int toRgb(float hueDegrees, float saturation, float value) {
        float chroma = value * saturation;
        float hue = wrapHue(hueDegrees) / 60.0F;
        float second = chroma * (1.0F - Math.abs(hue % 2.0F - 1.0F));
        float red;
        float green;
        float blue;
        if (hue < 1.0F) {
            red = chroma;
            green = second;
            blue = 0.0F;
        } else if (hue < 2.0F) {
            red = second;
            green = chroma;
            blue = 0.0F;
        } else if (hue < 3.0F) {
            red = 0.0F;
            green = chroma;
            blue = second;
        } else if (hue < 4.0F) {
            red = 0.0F;
            green = second;
            blue = chroma;
        } else if (hue < 5.0F) {
            red = second;
            green = 0.0F;
            blue = chroma;
        } else {
            red = chroma;
            green = 0.0F;
            blue = second;
        }
        float lift = value - chroma;
        return byteOf(red + lift) << 16 | byteOf(green + lift) << 8 | byteOf(blue + lift);
    }

    private static int byteOf(float channel) {
        return Math.round(clamp(channel) * 255.0F);
    }

    private static float clamp(float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
    }

    private static float wrapHue(float hue) {
        float wrapped = hue % 360.0F;
        return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
    }
}
