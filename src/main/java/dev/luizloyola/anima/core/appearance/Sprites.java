package dev.luizloyola.anima.core.appearance;

import org.jspecify.annotations.Nullable;

/**
 * Where a {@link Part}'s texture comes from — the one thing the compositor cannot do for itself.
 *
 * <p>The appearance editor decodes PNGs off disk with {@code ImageIO}, the game pulls them through
 * the resource manager as {@code NativeImage}s, and both are unnameable from {@code core}.
 *
 * <p>Implementations are expected to cache: a bake asks for each part's texture once, but a
 * settlement's worth of bakes asks for the same handful constantly.
 */
@FunctionalInterface
public interface Sprites {

    /**
     * The texture with this id, or {@code null} if there is none.
     *
     * <p>Null rather than a throw: a catalog naming art a pack does not ship is an ordinary
     * condition, and the right response is to skip that part and draw the rest.
     */
    @Nullable Sprite get(String textureId);
}
