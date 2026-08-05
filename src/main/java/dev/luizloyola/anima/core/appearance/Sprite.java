package dev.luizloyola.anima.core.appearance;

import java.util.Objects;

/**
 * A decoded image: dimensions and straight ARGB pixels, row-major.
 *
 * <p>Not a Minecraft {@code NativeImage} and not an AWT {@code BufferedImage}: the
 * compositor is run by both the game and the appearance editor, so it must name neither. Each side
 * decodes into this and reads back out of it.
 *
 * <p><b>ARGB, not ABGR.</b> {@code NativeImage} exposes both spellings; this type picks one, the
 * {@code compat} layer converts, and tests assert it.
 */
public record Sprite(int width, int height, int[] argb) {
    public Sprite {
        Objects.requireNonNull(argb, "argb");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a sprite of " + width + "x" + height + " holds nothing");
        }
        if (argb.length != width * height) {
            throw new IllegalArgumentException(
                    "sprite is " + width + "x" + height + " but carries " + argb.length + " pixels");
        }
    }

    /** A transparent sprite of the given size. */
    public static Sprite blank(int width, int height) {
        return new Sprite(width, height, new int[width * height]);
    }

    public int pixel(int x, int y) {
        return argb[y * width + x];
    }

    public boolean contains(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
}
