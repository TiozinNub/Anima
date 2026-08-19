package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.Places;
import org.junit.jupiter.api.Test;

/**
 * A claim is {@code Places}'s row, not this store's. If {@code entries()} ever read the composed
 * {@code AgentKnowledge.all(kind)} instead of the uncomposed {@code sighted(kind)}, a claim would
 * be written here as a private sighting and double-persisted — and it would outlive the party
 * membership that made it visible, since {@code Places.View} stops showing it on the next read
 * but nothing would ever remove the leaked copy from this file.
 */
class KnowledgeDataTest {

    private static final PoiKind BENCH = PoiKind.register("test_knowledge_data_bench", 1, "");

    @Test
    void aFoundedClaimNeverEntersTheKnowledgeFile() {
        AgentId hazel = AgentId.random();
        Places places = new Places();
        places.found(BENCH, new Pos(1, 64, 1), hazel, null, 1L);

        KnowledgeData data = new KnowledgeData();
        data.registry().sees(places, () -> 0L);
        data.registry().forPerson(hazel); // hazel's mind gets touched, as any interaction would

        assertTrue(data.entries().isEmpty(),
                "the claim must not become this store's own row — that double-persists it and "
                        + "leaks it as a sighting that outlives the party membership that made it "
                        + "visible");
        assertEquals(0, data.actualRows(), "the boot guard's own count must agree with entries()");
    }
}
