package dev.luizloyola.anima.compat.client.appearance;

import com.mojang.blaze3d.platform.NativeImage;
import dev.luizloyola.anima.core.appearance.Sprite;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
//? if >=26.4-alpha {
/*import net.minecraft.client.renderer.texture.TextureResources;
*///?} else {
import net.minecraft.client.renderer.texture.DynamicTexture;
//?}

/**
 * A baked sprite on the GPU, registered with the texture manager. Releasing every name it was
 * registered under closes it; nothing else does.
 *
 * <p><b>Who closes the image differs.</b> Before 26.4 a {@code DynamicTexture} keeps the
 * {@code NativeImage} and closes it with itself. 26.4's {@code TextureResources} only uploads it,
 * so the image is closed here as soon as the upload is done.
 */
@Environment(EnvType.CLIENT)
public final class BakedTexture {
    //? if >=26.4-alpha {
    /*private final TextureResources texture;

    private BakedTexture(TextureResources texture) {
        this.texture = texture;
    }

    public static BakedTexture upload(Identifier id, Sprite sprite) {
        try (NativeImage image = NativeImages.imageOf(sprite)) {
            return new BakedTexture(TextureResources.from2dImage(id::toString, image));
        }
    }
    *///?} else {
    private final DynamicTexture texture;

    private BakedTexture(DynamicTexture texture) {
        this.texture = texture;
    }

    public static BakedTexture upload(Identifier id, Sprite sprite) {
        NativeImage image = NativeImages.imageOf(sprite);
        return new BakedTexture(new DynamicTexture(id::toString, image));
    }
    //?}

    /** Puts this texture up under {@code name}; one texture may be registered under several. */
    public void register(Identifier name) {
        Minecraft.getInstance().getTextureManager().register(name, texture);
    }

    /** Writes the texture as PNG under {@code directory}. */
    public void dump(Identifier id, Path directory) throws IOException {
        texture.dumpContents(id, directory);
    }
}
