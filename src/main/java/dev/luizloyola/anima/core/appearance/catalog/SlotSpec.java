package dev.luizloyola.anima.core.appearance.catalog;

import java.util.List;
import java.util.Objects;

/**
 * One authored layer: where it sits, what recolours it, and which art it wears when.
 *
 * <p>Position is always <em>anchor plus offset</em>, never a raw coordinate — see {@link Anchor}.
 * Size defaults to the anchor's own, which makes a whole-canvas layer a slot with nothing but a
 * name and an anchor.
 *
 * <p>{@link #dynamic()} is the churn classification the bake's static/dynamic split rests on: a
 * slot is dynamic only if its selector reads a parameter that changes more than once per in-game
 * day. Default false.
 *
 * <p>{@link #optional()} says this layer is <em>allowed</em> to draw nothing — a beard, blood,
 * grime — which is why the editor does not complain that its selector has no {@code *} rule.
 */
public record SlotSpec(String name, String anchor, int offsetX, int offsetY,
                       int width, int height, boolean dynamic, boolean optional,
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

    /** A slot that always draws — the common case, and the one a missing rule is a bug in. */
    public SlotSpec(String name, String anchor, int offsetX, int offsetY,
                    int width, int height, boolean dynamic,
                    List<OpSpec> ops, Selector selector) {
        this(name, anchor, offsetX, offsetY, width, height, dynamic, false, ops, selector);
    }

    /** Whether this slot's sprite stays inside its anchor — the editor's red-outline check. */
    public boolean fitsWithin(Anchor within) {
        return within.contains(offsetX, offsetY, width, height);
    }
}
