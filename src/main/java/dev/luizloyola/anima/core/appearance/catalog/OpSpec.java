package dev.luizloyola.anima.core.appearance.catalog;

import dev.luizloyola.anima.core.appearance.ColorOp;
import dev.luizloyola.anima.core.appearance.Colors;
import dev.luizloyola.anima.core.appearance.RampSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link ColorOp} as a catalog can write one — with the wearer's colours still unresolved.
 *
 * <p>Two hierarchies because a catalog is written once and a colour is chosen per agent.
 * {@link #resolve} is the only place bindings and ramp names are looked up.
 *
 * <p>Mirrors {@link ColorOp}'s shape, including the list-not-nesting decision, so a catalog's op
 * list and a recipe's read the same and hash predictably.
 */
public sealed interface OpSpec {

    /** The concrete op for one agent's colours. */
    ColorOp resolve(Map<String, Integer> bindings, Map<String, RampSpec> ramps);

    record Multiply(Tint tint) implements OpSpec {
        public Multiply {
            Objects.requireNonNull(tint, "tint");
        }

        @Override
        public ColorOp resolve(Map<String, Integer> bindings, Map<String, RampSpec> ramps) {
            return new ColorOp.Multiply(tint.resolve(bindings));
        }
    }

    record Hsv(float hueDegrees, float satMul, float valMul) implements OpSpec {
        @Override
        public ColorOp resolve(Map<String, Integer> bindings, Map<String, RampSpec> ramps) {
            return new ColorOp.Hsv(hueDegrees, satMul, valMul);
        }
    }

    record Palette(List<Swap> swaps) implements OpSpec {
        public Palette {
            swaps = List.copyOf(Objects.requireNonNull(swaps, "swaps"));
        }

        @Override
        public ColorOp resolve(Map<String, Integer> bindings, Map<String, RampSpec> ramps) {
            List<ColorOp.Swap> resolved = new ArrayList<>(swaps.size());
            for (Swap swap : swaps) {
                resolved.add(new ColorOp.Swap(swap.fromRgb(), swap.to().resolve(bindings)));
            }
            return new ColorOp.Palette(resolved);
        }

        /** One replacement: an exact authored colour, and the tint that stands in for it. */
        public record Swap(int fromRgb, Tint to) {
            public Swap {
                Objects.requireNonNull(to, "to");
            }
        }
    }

    /**
     * A ramp named rather than spelled out: a catalog holds its own ramp table, and one curve is
     * shared by every layer that uses it.
     *
     * <p>⚠️ An unknown ramp name is a hard error at resolve time, unlike a missing binding or
     * texture: those have a fallback and a ramp does not, since guessing a curve would silently
     * produce shading nobody chose.
     */
    record Ramp(Tint base, String ramp) implements OpSpec {
        public Ramp {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(ramp, "ramp");
        }

        @Override
        public ColorOp resolve(Map<String, Integer> bindings, Map<String, RampSpec> ramps) {
            RampSpec spec = ramps.get(ramp);
            if (spec == null) {
                throw new IllegalArgumentException("no ramp named '" + ramp + "' in this catalog");
            }
            return new ColorOp.Ramp(base.resolve(bindings), spec);
        }
    }

    /**
     * Shift a whole image so that one colour in it becomes another — everything else moving with it.
     *
     * <p>For art that is a <b>painting rather than a shade map</b>: a ramp replaces a handful of
     * listed colours, useless for a hand-painted body carrying a hundred and thirty. This measures
     * the HSV difference between a reference colour and the target and applies it to every pixel,
     * so the relationships an artist painted survive.
     *
     * <p>It resolves to a plain {@link ColorOp.Hsv}, so nothing downstream learns a new case.
     *
     * <p>⚠️ Saturation and value are <b>ratios</b>. A greyscale reference can never reach a coloured
     * target, so this op leaves saturation alone rather than dividing by it, and
     * {@link ColorOp.Multiply} is the right tool for grey art. Shifting much lighter <b>clips</b>: a
     * pixel brighter than the ratio allows lands at white and flattens against its neighbours, and
     * the editor counts those.
     */
    record Retint(int fromRgb, Tint to) implements OpSpec {
        public Retint {
            Objects.requireNonNull(to, "to");
        }

        @Override
        public ColorOp resolve(Map<String, Integer> bindings, Map<String, RampSpec> ramps) {
            float[] from = Colors.toHsv(fromRgb);
            float[] target = Colors.toHsv(to.resolve(bindings));
            return new ColorOp.Hsv(
                    target[0] - from[0],
                    from[1] < 1e-4F ? 1.0F : target[1] / from[1],
                    from[2] < 1e-4F ? 1.0F : target[2] / from[2]);
        }
    }

    /** Resolve a whole list, in order. */
    static List<ColorOp> resolveAll(List<OpSpec> specs,
                                    Map<String, Integer> bindings,
                                    Map<String, RampSpec> ramps) {
        List<ColorOp> ops = new ArrayList<>(specs.size());
        for (OpSpec spec : specs) {
            ops.add(spec.resolve(bindings, ramps));
        }
        return ops;
    }
}
