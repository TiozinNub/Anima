package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.social.speech.Encounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

/**
 * What a consumer's act does when it is said — the hook Anima's own effects ({@code accept_offer},
 * {@code accept_join}) do without, because they live here. Register at mod init; every engine's
 * listener fires them once per line, from the speaker's engine.
 */
public final class SpeechEffects {

    /** The line said at {@code index} of {@code e}. */
    public interface Effect {
        void said(MinecraftServer server, Encounter e, int index);
    }

    private static final Map<String, List<Effect>> BY_ACT = new ConcurrentHashMap<>();

    private SpeechEffects() {
    }

    public static void on(String act, Effect effect) {
        BY_ACT.computeIfAbsent(act, key -> new ArrayList<>()).add(effect);
    }

    static void fire(MinecraftServer server, Encounter e, int index) {
        for (Effect effect : BY_ACT.getOrDefault(e.transcript().get(index).act(), List.of())) {
            effect.said(server, e, index);
        }
    }
}
