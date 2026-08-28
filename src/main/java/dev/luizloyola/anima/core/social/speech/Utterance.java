package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One line of a transcript. A null author is the SYSTEM speaker — what the world did to the
 * conversation, written by whoever noticed; its subject rides in the payload so both parties
 * noticing writes once.
 */
public record Utterance(@Nullable AgentId author, String act, Map<String, String> payload, long tick) {

    public static final String SUBJECT = "subject";

    public Utterance {
        Objects.requireNonNull(act, "act");
        payload = Map.copyOf(payload == null ? Map.of() : payload);
    }

    public boolean system() {
        return author == null;
    }

    public static Utterance system(String act, AgentId subject, long tick) {
        return new Utterance(null, act, Map.of(SUBJECT, subject.toString()), tick);
    }
}
