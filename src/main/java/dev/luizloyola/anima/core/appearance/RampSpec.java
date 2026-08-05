package dev.luizloyola.anima.core.appearance;

import java.util.List;
import java.util.Objects;

/**
 * A shade ramp: how one base colour becomes the several shades a piece of pixel art is drawn in.
 *
 * <p>A multiply <em>darkens and nothing else</em>, while real pixel art shifts <em>hue</em> into
 * shadow (toward blue and violet), and toward yellow in light. A ramp is therefore a list of
 * {@link Shade}s, each a hue rotation and a saturation and value multiplier on the base colour.
 *
 * <p>Every field is <b>already fixed point</b> — tenths of a degree for hue, thousandths for the
 * multipliers — which keeps a ramp out of the float-quantisation problem on {@link Canonical}.
 *
 * <p><b>The name is load-bearing.</b> A ramp is canonicalised by {@link #name()} alone, so <b>the
 * bake cache must be cleared whenever the ramp table is reloaded</b>, or shades retuned behind an
 * unchanged name keep serving the texture baked from the old curve.
 *
 * <h2>Keys: the colours the art is drawn in</h2>
 * {@link #keys} is the {@link #steps()} authored colours the generated shades replace. Left empty
 * it means {@link Shades}' reserved encoding; declared, it lets a layer be <b>drawn in real
 * colours</b> an artist can see and paint, at the cost stated on {@link Shades}.
 *
 * <p>⚠️ <b>Keys belong to the ramp, not to the operation using it.</b> {@link Canonical} spells a
 * ramp as its base colour and its <em>name</em>, so two parts naming one ramp with different keys
 * would hash identically while baking to different pixels, and the second would be served the
 * first's cached texture.
 */
public record RampSpec(String name, List<Shade> shades, List<Integer> keys) {
    public RampSpec {
        Objects.requireNonNull(name, "name");
        shades = List.copyOf(Objects.requireNonNull(shades, "shades"));
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a ramp must be named — the name is its identity in the hash");
        }
        if (shades.isEmpty()) {
            throw new IllegalArgumentException("ramp " + name + " has no shades");
        }
        if (!keys.isEmpty() && keys.size() != shades.size()) {
            throw new IllegalArgumentException("ramp " + name + " declares " + keys.size()
                    + " key colour(s) for " + shades.size() + " shade(s) — there must be one key per shade");
        }
        // A repeated key silently loses a shade: the lookup keeps one mapping and the other shade
        // is never reachable, which reads as two bands of the art collapsing into one.
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if ((keys.get(i) & 0xFFFFFF) == (keys.get(j) & 0xFFFFFF)) {
                    throw new IllegalArgumentException(String.format(
                            "ramp %s uses #%06x for both shade %d and shade %d — keys must be distinct",
                            name, keys.get(i) & 0xFFFFFF, i, j));
                }
            }
        }
    }

    /** A ramp drawn in {@link Shades}' reserved encoding — the default convention. */
    public RampSpec(String name, List<Shade> shades) {
        this(name, shades, List.of());
    }

    /** One step of a ramp, in fixed point: hue in tenths of a degree, the multipliers in thousandths. */
    public record Shade(int hueShift10, int satMul1000, int valMul1000) {}

    /** The authored colour that shade {@code index} replaces. */
    public int keyAt(int index) {
        return keys.isEmpty() ? Shades.color(index) : keys.get(index) & 0xFFFFFF;
    }

    /** Whether {@code rgb} is one of this ramp's keys — the validation pass's question. */
    public boolean isKey(int rgb) {
        int wanted = rgb & 0xFFFFFF;
        if (keys.isEmpty()) {
            int index = Shades.indexOf(wanted);
            return index >= 0 && index < shades.size();
        }
        for (int key : keys) {
            if ((key & 0xFFFFFF) == wanted) {
                return true;
            }
        }
        return false;
    }

    public int steps() {
        return shades.size();
    }
}
