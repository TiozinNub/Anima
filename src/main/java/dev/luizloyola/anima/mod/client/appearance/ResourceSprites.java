package dev.luizloyola.anima.mod.client.appearance;

import dev.luizloyola.anima.compat.client.appearance.NativeImages;
import dev.luizloyola.anima.core.appearance.Sprite;
import dev.luizloyola.anima.core.appearance.Sprites;
import dev.luizloyola.anima.mod.AnimaMod;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * The game's half of the {@link Sprites} seam: a part's texture id, resolved through the resource
 * pack stack and decoded once. The editor's half reads the same PNGs with {@code ImageIO} and the
 * compositor knows about neither, so art authored in the tool is identical in the world.
 *
 * <h2>The id a part carries</h2>
 * An <b>asset id</b> — {@code minecraft:entity/player/wide/steve} — no {@code textures/}, no
 * {@code .png}: the form {@code Appearance} stores and the catalog is authored in. Turning it into
 * a resource path here is the same transformation {@code ClientAsset.ResourceTexture} applies to
 * its one-argument form.
 *
 * <h2>Misses are cached too</h2>
 * Art a pack does not ship is ordinary and the compositor skips that part, but the lookup runs on
 * the render thread: an unremembered miss is a resource-manager probe <em>per frame, per agent,
 * forever</em>, and caching it fires the warning once per reload, not sixty times a second.
 */
@Environment(EnvType.CLIENT)
public final class ResourceSprites implements Sprites {

    /** Decoded art, and the misses, keyed by the id a {@code Part} carries. */
    private final Map<String, Optional<Sprite>> decoded = new HashMap<>();

    @Override
    public @Nullable Sprite get(String textureId) {
        return this.decoded.computeIfAbsent(textureId, ResourceSprites::load).orElse(null);
    }

    /**
     * Drop everything, so the next bake reads the packs as they are now. Called on a resource
     * reload: a decoded sprite is a snapshot of a pack that may no longer be loaded, and a
     * <em>miss</em> a snapshot of one that may have just gained the file.
     */
    public void clear() {
        this.decoded.clear();
    }

    /** How many textures are currently held, present or absent. Diagnostics only. */
    public int size() {
        return this.decoded.size();
    }

    private static Optional<Sprite> load(String textureId) {
        Identifier asset = Identifier.tryParse(textureId);
        if (asset == null) {
            // tryParse rather than parse: this runs mid-render, and a malformed id in a data pack
            // must cost that part rather than the frame it was drawn in.
            AnimaMod.LOGGER.warn("appearance: '{}' is not a texture id, so nothing draws for it", textureId);
            return Optional.empty();
        }
        Identifier path = asset.withPath(name -> "textures/" + name + ".png");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(path);
        if (resource.isEmpty()) {
            AnimaMod.LOGGER.warn("appearance: no texture at {} — that layer will be missing", path);
            return Optional.empty();
        }
        try (InputStream stream = resource.get().open()) {
            return Optional.of(NativeImages.read(stream));
        } catch (IOException unreadable) {
            AnimaMod.LOGGER.warn("appearance: {} could not be read: {}", path, unreadable.getMessage());
            return Optional.empty();
        }
    }
}
