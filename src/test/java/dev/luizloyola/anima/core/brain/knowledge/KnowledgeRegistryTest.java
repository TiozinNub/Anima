package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.Places;
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
                .note(new PoiMemory(TestPois.TREE, anchor, Region.of(anchor), 6, false, 100),
                        AgentKnowledge.maxPerKind(TestSpecies.PROFILE));

        assertEquals(1, registry.forPerson(person(1)).size());
        assertEquals(0, registry.forPerson(person(2)).size(),
                "no ESP between persons either: each must see it themselves");
    }

    @Test
    void seesRePointsKnowledgeMintedBeforeTheStoreArrived() {
        // Knowledge is minted lazily and the store is wired at server start, so whoever asked
        // first — as every test in this class does — must not be stuck looking at Places.EMPTY.
        PoiKind bench = PoiKind.register("test_registry_bench", 1, "");
        KnowledgeRegistry registry = new KnowledgeRegistry();
        AgentKnowledge early = registry.forPerson(person(1));

        Places places = new Places();
        Pos at = new Pos(4, 64, 4);
        places.found(bench, at, person(1), null, 5L);
        registry.sees(places, () -> 42L);

        assertEquals(at, early.nearest(bench, new Pos(0, 0, 0)).orElseThrow().anchor(),
                "the object minted before the store arrived must still see what arrived after");
        assertEquals(42L, early.nearest(bench, new Pos(0, 0, 0)).orElseThrow().lastSeenTick(),
                "and the clock installed alongside it, not the founding time");
    }
}
