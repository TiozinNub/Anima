package dev.luizloyola.anima.core.appearance;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Turns a {@link Recipe} into pixels — the one piece of this feature the game and the appearance
 * editor both run, so it names no Minecraft and no AWT type. Textures arrive through
 * {@link Sprites}; everything else here is arithmetic.
 *
 * <p>Cost is trivial: a part is at most 64×64 = 4096 pixels, its colour operations compile to a
 * lookup first, and a whole person is a handful of parts. The expense was never the composite but
 * recompositing a stack to move a mouth, which the static/dynamic split in {@link Recipe} removes.
 */
public final class Compositor {
    private Compositor() {}

    public static final int NOBODY = -1;

    /**
     * A finished bake: the picture, and who drew each pixel.
     *
     * <p>{@link #partIds()} indexes into {@link Recipe#all()}, or is {@link #NOBODY}. One array
     * buys the editor click-to-select: the topmost part under a cursor is an array read, correct
     * through transparency.
     */
    public record Bake(Sprite image, int[] partIds) {
        /** Which part owns this pixel, or {@link #NOBODY}. */
        public int partAt(int x, int y) {
            return image.contains(x, y) ? partIds[y * image.width() + x] : NOBODY;
        }
    }

    /**
     * Composite every part of a recipe, statics first, then the dynamics drawn over them.
     *
     * <p>A part whose texture {@link Sprites} does not have is <b>skipped</b>, not fatal: a catalog
     * naming a hairstyle a pack does not ship must cost that hairstyle and not the body wearing it.
     */
    public static Bake bake(Recipe recipe, Sprites sprites) {
        int width = recipe.width();
        int height = recipe.height();
        int[] canvas = new int[width * height];
        int[] owners = new int[width * height];
        Arrays.fill(owners, NOBODY);

        List<Part> parts = recipe.all();
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            Sprite sprite = sprites.get(part.texture());
            if (sprite != null) {
                draw(canvas, owners, width, height, part, sprite, index);
            }
        }
        return new Bake(new Sprite(width, height, canvas), owners);
    }

    /**
     * Draw one part. Iterates the part's <em>declared</em> box and reads the sprite at the matching
     * local coordinate, so a sprite larger than its slot is cropped and one smaller covers less;
     * neither is an error here, and the editor reports the mismatch.
     */
    private static void draw(int[] canvas, int[] owners, int width, int height,
                             Part part, Sprite sprite, int index) {
        IntUnaryOperator recolour = Colors.compile(part.ops());
        for (int localY = 0; localY < part.h(); localY++) {
            int canvasY = part.y() + localY;
            if (canvasY < 0 || canvasY >= height || localY >= sprite.height()) {
                continue;
            }
            for (int localX = 0; localX < part.w(); localX++) {
                int canvasX = part.x() + localX;
                if (canvasX < 0 || canvasX >= width || localX >= sprite.width()) {
                    continue;
                }
                int source = sprite.pixel(localX, localY);
                if ((source >>> 24) == 0) {
                    continue; // fully transparent: never recoloured, never an owner
                }
                int target = canvasY * width + canvasX;
                canvas[target] = over(recolour.applyAsInt(source), canvas[target]);
                owners[target] = index;
            }
        }
    }

    /**
     * Source-over: {@code source} composited onto {@code under}, both straight (non-premultiplied)
     * ARGB.
     *
     * <p>Public because anything that stacks these pixels a second time — the editor's paper doll —
     * must stack them the same way or it shows something the game will not.
     */
    public static int over(int source, int under) {
        int sourceAlpha = source >>> 24;
        if (sourceAlpha == 0xFF) {
            return source;
        }
        int underAlpha = under >>> 24;
        int outAlpha = sourceAlpha + underAlpha * (255 - sourceAlpha) / 255;
        if (outAlpha == 0) {
            return 0;
        }
        return outAlpha << 24
                | channel(source, under, 16, sourceAlpha, underAlpha, outAlpha) << 16
                | channel(source, under, 8, sourceAlpha, underAlpha, outAlpha) << 8
                | channel(source, under, 0, sourceAlpha, underAlpha, outAlpha);
    }

    private static int channel(int source, int under, int shift,
                               int sourceAlpha, int underAlpha, int outAlpha) {
        int top = (source >> shift & 0xFF) * sourceAlpha;
        int bottom = (under >> shift & 0xFF) * underAlpha * (255 - sourceAlpha) / 255;
        return Math.min(255, (top + bottom) / outAlpha);
    }
}
