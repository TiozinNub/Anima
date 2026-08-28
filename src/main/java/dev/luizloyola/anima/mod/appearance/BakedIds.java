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
}
