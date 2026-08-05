package dev.luizloyola.anima.core.appearance.catalog;

import java.util.List;
import java.util.Objects;

/**
 * One authored layer: where it sits, what recolours it, and which art it wears when.
 *
 * <p>Position is always <em>anchor plus offset</em>, never a raw coordinate — see {@link Anchor}
 * for why. Size defaults to the anchor's own, so a whole-canvas layer (a body, a set of clothes) is
 * a slot with nothing but a name and an anchor.
 *
 * <p>{@link #dynamic()} is the churn classification the bake's static/dynamic split rests on: a
 * slot is dynamic only if its selector reads a parameter that changes more than once per in-game
 * day. Default false.
 */
public record SlotSpec(String name, String anchor, int offsetX, int offsetY,
                       int width, int height, boolean dynamic,
                       List<OpSpec> ops, Selector selector) {
    public SlotSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(anchor, "anchor");
        ops = List.copyOf(Objects.requireNonNull(ops, "ops"));
        Objects.requireNonNull(selector, "selector");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("slot " + name + " covers nothing");
        }
    }

    /** Whether this slot's sprite stays inside its anchor — the editor's red-outline check. */
    public boolean fitsWithin(Anchor within) {
        return within.contains(offsetX, offsetY, width, height);
    }
}
