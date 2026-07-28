package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.agent.AgentId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The AgentId-keyed roster of knowledge stores: lazy, stable, per-person isolated. */
class KnowledgeRegistryTest {

    private static AgentId person(long seed) {
        return new AgentId(new UUID(seed, seed));
    }

    @Test
    void forPersonCreatesLazilyAndReturnsTheSameStore() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        assertTrue(registry.persons().isEmpty());

        AgentKnowledge knowledge = registry.forPerson(person(1));
        assertSame(knowledge, registry.forPerson(person(1)));
        assertEquals(1, registry.persons().size());
    }

    @Test
    void memoriesArePerPerson() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        Pos anchor = new Pos(10, 64, 0);
        registry.forPerson(person(1))
                .note(new PoiMemory(TestPois.TREE, anchor, Region.of(anchor), 6, false, 100));

        assertEquals(1, registry.forPerson(person(1)).size());
        assertEquals(0, registry.forPerson(person(2)).size(),
                "no ESP between persons either: each must see it themselves");
    }
}
