package dev.luizloyola.anima.core.appearance.catalog;

import java.util.Objects;

/**
 * A named region of the canvas that parts are placed against.
 *
 * <p>Names rather than raw coordinates scattered through a catalog: moving every face down a pixel
 * is one edit, a body variant whose head sits differently is a different anchor set, and a sprite
 * that overflows its region is <em>checkable</em> — an editor warning rather than a pixel bleeding
 * across a UV seam.
 */
public record Anchor(String name, int x, int y, int width, int height) {
    public Anchor {
        Objects.requireNonNull(name, "name");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("anchor " + name + " covers nothing");
        }
    }

    /** Whether a sprite of this size, at this offset, stays inside the region. */
    public boolean contains(int offsetX, int offsetY, int spriteWidth, int spriteHeight) {
        return offsetX >= 0 && offsetY >= 0
                && offsetX + spriteWidth <= width
                && offsetY + spriteHeight <= height;
    }
}
