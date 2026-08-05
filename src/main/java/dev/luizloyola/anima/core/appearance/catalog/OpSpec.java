package dev.luizloyola.anima.core.appearance.catalog;

import dev.luizloyola.anima.core.appearance.ColorOp;
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
