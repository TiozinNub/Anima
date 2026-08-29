package dev.luizloyola.anima.mod.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The baked-texture id format, pinned. Both spellings are cheap to write by hand and impossible to
 * check by eye: a wrong one is not an exception anywhere, it is a face that renders as the missing
 * texture on somebody else's screen — which is exactly how the chat glyph shipped.
 */
class BakedIdsTest {

    /** Hex, lower case, zero-padded: the same string on the server that bakes nothing. */
    private static final long HASH = 0xABCL;

    @Test
    void theRawIdIsTheHashUnderBaked() {
        assertEquals(Identifier.fromNamespaceAndPath("anima", "baked/0000000000000abc"),
                BakedIds.of(HASH));
    }

    @Test
    void theChatGlyphResolvesTheSameTextureThroughATexturesPath() {
        // What ResolvableProfile's skin asset expands to on the client — namespace kept, the raw
        // path wrapped in textures/….png. Anything else is a missing texture beside every line.
        assertEquals(
                Identifier.fromNamespaceAndPath("anima", "textures/baked/0000000000000abc.png"),
                BakedIds.texturePathOf(HASH));
    }
}
