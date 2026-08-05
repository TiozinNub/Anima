package dev.luizloyola.anima.core.appearance;

import java.util.List;
import java.util.Objects;

/**
 * A shade ramp: how one base colour becomes the several shades a piece of pixel art is drawn in.
 *
 * <p>A multiply <em>darkens and nothing else</em>, where real pixel art shifts <em>hue</em> into
 * shadow (toward blue and violet), and toward yellow in light. So a ramp is a small list of
 * {@link Shade}s, each a hue rotation plus a saturation and value multiplier on the base colour,
 * not a brightness curve.
 *
 * <p>Every field is <b>already fixed point</b> — tenths of a degree for hue, thousandths for the
 * multipliers — which keeps a ramp out of the float-quantisation problem described on
 * {@link Canonical}.
 *
 * <p><b>The name is load-bearing.</b> A ramp is canonicalised by {@link #name()} alone, which keeps
 * the hashed stream short — so <b>the bake cache must be cleared whenever the ramp table is
 * reloaded</b>, or retuned shades behind an unchanged name keep serving the old texture.
 */
public record RampSpec(String name, List<Shade> shades) {
    public RampSpec {
        Objects.requireNonNull(name, "name");
        shades = List.copyOf(Objects.requireNonNull(shades, "shades"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a ramp must be named — the name is its identity in the hash");
        }
        if (shades.isEmpty()) {
            throw new IllegalArgumentException("ramp " + name + " has no shades");
        }
    }

    /** One step of a ramp, in fixed point: hue in tenths of a degree, the multipliers in thousandths. */
    public record Shade(int hueShift10, int satMul1000, int valMul1000) {}

    public int steps() {
        return shades.size();
    }
}
