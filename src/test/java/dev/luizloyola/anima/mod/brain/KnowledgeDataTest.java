package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.social.Places;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
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

    @Test
    void whatWasSeenInsideRoundTripsThroughJson() {
        AgentId hazel = AgentId.random();
        Pos at = new Pos(4, 64, 4);

        KnowledgeData data = new KnowledgeData();
        data.registry().forPerson(hazel)
                .sawInside(at, List.of(ItemStack.of("minecraft:oak_log", 32, 64)), 100L,
                        AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        assertEquals(1, data.actualRows(),
                "a contents belief with no POI beside it is still a row — entries() must not "
                        + "require pois or sightings too, or this person's whole knowledge is "
                        + "silently dropped from the file");

        var encoded = KnowledgeData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        KnowledgeData decoded = KnowledgeData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        AgentKnowledge.Seen seen = decoded.registry().forPerson(hazel).insideOf(at).orElseThrow();
        assertEquals(1, seen.stacks().size());
        assertEquals("minecraft:oak_log", seen.stacks().get(0).id());
        assertEquals(32, seen.stacks().get(0).count());
        assertEquals(100L, seen.seenTick(), "the tick it was seen is what prices the walk later");
    }

    @Test
    void aPersonWithNoInsidesFieldStillParses() {
        // The exact shape of every knowledge.dat written before a body could open a container:
        // built by hand, not by this codec's own encoder, so a regression that made "insides"
        // required would fail this specific parse rather than the round trip it wrote itself.
        UUID id = UUID.randomUUID();
        JsonObject person = new JsonObject();
        person.add("id", UUIDUtil.CODEC.encodeStart(JsonOps.INSTANCE, id).getOrThrow());
        person.add("pois", new JsonArray());
        JsonArray persons = new JsonArray();
        persons.add(person);
        JsonObject root = new JsonObject();
        root.add("persons", persons);

        KnowledgeData decoded = KnowledgeData.CODEC.parse(JsonOps.INSTANCE, root).getOrThrow();

        AgentId who = AgentId.of(id);
        assertTrue(decoded.registry().persons().contains(who), "the pre-existing person still loads");
        assertTrue(decoded.registry().forPerson(who).insides().isEmpty(),
                "no insides key means nothing was ever looked in — the empty default, not an error, "
                        + "which is what makes an old file indistinguishable from a store nobody "
                        + "has opened yet");
    }
}
