package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A consumer's act does what it registered, on its own line only. */
class SpeechEffectsTest {

    @Test
    void anEffectFiresForItsActAndNoOther() {
        List<Integer> fired = new ArrayList<>();
        SpeechEffects.on("test_effect_act", (server, e, index) -> fired.add(index));
        AgentId ada = AgentId.random();
        Encounter e = new Encounter(UUID.randomUUID(), List.of(ada, AgentId.random()), 0);
        e.append(new Utterance(ada, "greeting", Map.of(), 1));
        e.append(new Utterance(ada, "test_effect_act", Map.of(), 2));

        SpeechEffects.fire(null, e, 0);
        SpeechEffects.fire(null, e, 1);

        assertEquals(List.of(1), fired);
    }
}
