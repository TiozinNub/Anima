package dev.luizloyola.anima.core.appearance;

import java.util.List;
import java.util.Objects;

/**
 * One placed sprite in a {@link Recipe}: a texture, where it lands on the canvas, and the colour
 * operations applied to its pixels on the way down.
 *
 * <p>A whole-canvas layer is the degenerate case — a part at {@code (0,0)} sized to the canvas — so
 * one concept covers both a face of self-contained four-pixel pieces and a body drawn as a single
 * sheet, and a mood change redraws a small rectangle rather than recompositing a stack.
 *
 * <p><b>{@code texture} is a {@code String}, not a Minecraft {@code Identifier}</b>: this is
 * {@code core}, which names no Minecraft type, and the {@code compat} layer gives the string
 * meaning. That is what lets the plain-Java appearance preview tool run this same compositor over
 * the same catalog.
 */
public record Part(String texture, int x, int y, int w, int h, List<ColorOp> ops) {
    public Part {
        Objects.requireNonNull(texture, "texture");
        ops = List.copyOf(Objects.requireNonNull(ops, "ops"));
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("a part covering nothing is a mistake, not a no-op: " + w + "x" + h);
        }
    }

    /** A part drawn as authored, with no recolouring. */
    public static Part of(String texture, int x, int y, int w, int h) {
        return new Part(texture, x, y, w, h, List.of());
    }

    public static Part whole(String texture, int width, int height) {
        return of(texture, 0, 0, width, height);
    }
}
