package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.social.speech.Encounter;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Where a player's small talk finds something to say: the consumer's vocabulary, asked by the
 * player's seat before it falls back to a random flavour off the act. Anima has no vocabulary of
 * its own, so with nothing provided the fallback stands — {@code Choosers}' pattern.
 */
public final class PlayerTopics {

    /** A consumer's answer for a player, read off the player as a chooser reads a settler. */
    public interface Source {

        /**
         * The payload of a topic-bearing line for {@code player} in {@code e} — something they
         * have not already said there — or empty when nothing new is left.
         */
        Optional<Map<String, String>> pick(MinecraftServer server, ServerPlayer player, Encounter e);

        /** Whether anything new is left; the panel drops the chat button when not. */
        boolean anythingLeft(MinecraftServer server, ServerPlayer player, Encounter e);
    }

    private static volatile @Nullable Source source;

    private PlayerTopics() {
    }

    /** Called once from a consumer's init. */
    public static void provide(Source provided) {
        source = provided;
    }

    static Optional<Source> source() {
        return Optional.ofNullable(source);
    }
}
