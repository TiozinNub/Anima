package dev.luizloyola.anima.compat.client.appearance;

import com.mojang.blaze3d.platform.NativeImage;
import dev.luizloyola.anima.core.appearance.Sprite;
import java.io.IOException;
import java.io.InputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * PNG → pixels → something the GPU will take. {@code core} may not name {@code NativeImage} and
 * the appearance editor runs the same compositor with no Minecraft on its classpath, so both meet
 * at {@link Sprite}{@code (width, height, int[] argb)}.
 *
 * <p><b>ARGB.</b> {@code NativeImage} stores pixels ABGR and exposes both spellings of every
 * accessor; the unsuffixed pair is the ARGB one, delegating through {@code net.minecraft.util.ARGB}
 * ({@link Sprite}'s own convention), so this class uses it exclusively and converts exactly once,
 * inside vanilla. Verified in the bytecode of all three live targets.
 *
 * <p>Nothing here is version-specific today, so no Stonecutter comment; it lives in {@code compat}
 * as the seam where a future divergence would land.
 */
@Environment(EnvType.CLIENT)
public final class NativeImages {
    private NativeImages() {}

    /**
     * Decode a PNG. The image is closed before returning: its pixels are on the heap by then, and
     * one that outlived this call would be native memory with no owner.
     */
    public static Sprite read(InputStream stream) throws IOException {
        try (NativeImage image = NativeImage.read(stream)) {
            // getPixels() allocates a fresh ARGB array rather than aliasing the native buffer, so
            // handing it to Sprite is safe across the close above.
            return new Sprite(image.getWidth(), image.getHeight(), image.getPixels());
        }
    }

    /**
     * A fresh image holding a baked sprite's pixels. <b>The caller owns it</b>, and it is native
     * memory — invisible to a heap profiler if it leaks; the only caller hands it to a
     * {@code DynamicTexture}, which takes ownership and closes it.
     *
     * <p>Allocated zeroed (one 16 KB {@code calloc} per bake), so a future partial write cannot
     * upload whatever was in that memory before.
     */
    public static NativeImage imageOf(Sprite sprite) {
        NativeImage image = new NativeImage(sprite.width(), sprite.height(), true);
        for (int y = 0; y < sprite.height(); y++) {
            for (int x = 0; x < sprite.width(); x++) {
                image.setPixel(x, y, sprite.pixel(x, y));
            }
        }
        return image;
    }
}
