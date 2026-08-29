package dev.luizloyola.anima.mod.appearance;

import dev.luizloyola.anima.core.appearance.Canonical;
import dev.luizloyola.anima.core.appearance.Recipe;
import dev.luizloyola.anima.mod.AnimaMod;
import net.minecraft.resources.Identifier;

/**
 * The one place the baked-texture id format lives.
 *
 * <p>The client bakes the pixels and registers them under this name; the SERVER has to spell the
 * same name to put a head glyph in a chat line, and it never bakes anything. Two spellings of one
 * format is a bug that renders as a missing texture on somebody else's screen, so the format sits
 * in common code and both sides call it.
 */
public final class BakedIds {
    private BakedIds() {}

    /**
     * The texture id a {@link Recipe#hash()} gets. Hex, because a recipe's own spelling carries
     * colours and slashes and an {@link Identifier} path admits neither.
     */
    public static Identifier of(long recipeHash) {
        return Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "baked/" + Canonical.hex(recipeHash));
    }

    /**
     * The SECOND name the same texture answers to: what a chat glyph resolves it by.
     *
     * <p>A head glyph names its skin as a {@code ResolvableProfile} texture ASSET, and the client
     * expands one into {@code <namespace>:textures/<path>.png} before it asks the texture manager.
     * So a bake registered only under {@link #of} is a missing texture in every line the agent
     * speaks — which is exactly how it looked on screen. Derived from {@link #of} rather than
     * spelled a second time, for the reason this class exists at all.
     */
    public static Identifier texturePathOf(long recipeHash) {
        return of(recipeHash).withPath(path -> "textures/" + path + ".png");
    }
}
