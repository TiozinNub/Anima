package dev.luizloyola.anima.core.appearance.catalog;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A colour a catalog cannot know yet, because it belongs to whoever is being drawn.
 *
 * <p>Either a literal or the name of a binding ({@code SKIN}, {@code HAIR}, {@code EYE}) resolved
 * per agent at compose time. The literal doubles as the fallback for a binding nobody supplied, so
 * a catalog naming a binding a consumer does not have still draws something.
 */
public record Tint(@Nullable String binding, int rgb) {

    public static Tint literal(int rgb) {
        return new Tint(null, rgb);
    }

    public static Tint bound(String binding, int fallbackRgb) {
        return new Tint(binding, fallbackRgb);
    }

    public int resolve(Map<String, Integer> bindings) {
        if (binding == null) {
            return rgb;
        }
        Integer bound = bindings.get(binding);
        return bound == null ? rgb : bound;
    }
}
