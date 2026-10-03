package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
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
         * The payload of a line of the topic-bearing {@code act} for {@code player} in {@code e} —
         * something not already said there, or an answer to what was — or empty when nothing is
         * left to say with it.
         */
        Optional<Map<String, String>> pick(MinecraftServer server, ServerPlayer player, Encounter e,
                SpeechAct act);

        /** Whether {@code act} has anything left to say; the panel drops its button when not. */
        boolean anythingLeft(MinecraftServer server, ServerPlayer player, Encounter e, SpeechAct act);
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
