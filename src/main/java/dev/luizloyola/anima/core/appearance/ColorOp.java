package dev.luizloyola.anima.core.appearance;

import java.util.List;
import java.util.Objects;

/**
 * One colour transformation applied to a {@link Part}'s pixels. A part carries an <em>ordered
 * list</em> of these, applied left to right, and that is how a single authored PNG serves every
 * colour a consumer needs.
 *
 * <p><b>A list rather than a nesting {@code Then(a, b)} operator</b>, for correctness:
 * {@code Then(Then(a,b),c)} and {@code Then(a,Then(b,c))} are one transformation with two
 * spellings, so two hashes and two baked textures for one look. A list also loops rather than
 * recurses and serialises to a JSON array.
 *
 * <p><b>A sealed interface rather than an enum of blend modes</b>: a pattern match compiles to an
 * {@code invokedynamic typeSwitch}, while a {@code switch} over an enum compiles to a synthetic
 * {@code Foo$1.$SwitchMap} built in that class's {@code <clinit>}, which a hot swap never re-runs —
 * and the compositor is the most-edited class in this feature.
 *
 * @see Canonical for how each of these is spelled into the hashed stream
 */
public sealed interface ColorOp {

    /** Grey master × colour. The cheap one; it can only darken, so {@link Ramp} exists. */
    record Multiply(int rgb) implements ColorOp {}

    /**
     * Rotate hue, scale saturation and value — a wholesale recolour that preserves whatever value
     * relationships the artist drew. The one op holding floats; {@link Canonical} quantises them.
     */
    record Hsv(float hueDegrees, float satMul, float valMul) implements ColorOp {}

    /**
     * Replace exact colours with other exact colours. Authored art reserves a handful of index
     * colours and this maps them onto real ones. That is what gives an artist control over each
     * shade rather than over a single tint.
     *
     * <p>An ordered list rather than a map so it has one canonical spelling; the compositor builds
     * its own lookup.
     */
    record Palette(List<Swap> swaps) implements ColorOp {
        public Palette {
            swaps = List.copyOf(Objects.requireNonNull(swaps, "swaps"));
        }
    }

    /**
     * Shift a sprite so that <b>its own</b> main colour becomes {@code toRgb}, everything else
     * moving with it.
     *
     * <p>For a family whose members were drawn from different sources: two hairstyles taken off two
     * different skins share no palette, so no list of keys can serve both.
     *
     * <p>⚠️ Alone among these operations it <b>cannot be resolved without the pixels</b> — the
     * reference is the sprite's most common opaque colour, which only the compositor has. Hence a
     * distinct case rather than a measured {@link Hsv}: one op over two sprites is two shifts.
     *
     * <p>⚠️ Most-common is a judgement, not a law: it is the mid tone in every skin looked at so
     * far, and art whose deepest shade dominates re-bases off the shadow and comes out lighter than
     * intended.
     */
    record Retint(int toRgb) implements ColorOp {}

    /** {@link Palette}'s generated cousin: derive the shades from one base colour and a curve. */
    record Ramp(int baseRgb, RampSpec spec) implements ColorOp {
        public Ramp {
            Objects.requireNonNull(spec, "spec");
        }
    }

    record Swap(int fromRgb, int toRgb) {}
}
