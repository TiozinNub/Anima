package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The last of the three closed seams, opened: a consuming mod can say what a block <em>is</em>, not
 * merely what a place is ({@link PoiKind}) and how one grows ({@link GrowthRules}).
 *
 * <p>The half that turns a real blockstate into a kind lives in {@code BlockKinds} beside the level
 * probe and needs a world, so it is not tested here. What is here is that nothing in the pure
 * pipeline has an opinion about which kinds exist.
 */
class BlockKindTest {

    /** Something no part of Anima has ever heard of. */
    private static final BlockKind GOURD = BlockKind.register("test_gourd");

    private static final Pos HERE = new Pos(0, 64, 0);
    private static final double AHEAD = 0.0;

    /** A rule for it, of the kind a consumer would write. */
    private static final class GourdRule implements GrowthRule {
        static final PoiKind PATCH = PoiKind.register("test_gourd_patch", 4, " gourds");
        static final GourdRule INSTANCE = new GourdRule();

        @Override
        public PoiKind kind() {
            return PATCH;
        }

        @Override
        public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
            return kind == GOURD;
        }

        @Override
        public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
            return blocks.isEmpty() ? List.of()
                    : List.of(new Evaluation(List.copyOf(blocks.keySet()), blocks.size(), blocks));
        }
    }

    @AfterEach
    void forgetWhatGrows() {
        GrowthRules.reset();
    }

    @Test
    @DisplayName("instances are canonical per key, so == is safe across mods")
    void registrationIsCanonical() {
        assertSame(GOURD, BlockKind.register("test_gourd"),
                "registering the same key twice must hand back the same instance, or two mods "
                        + "would each hold their own idea of what a gourd is and neither would "
                        + "match the other in any of the == tests the pipeline is built out of");
        assertSame(BlockKind.LOG, BlockKind.register("log"));
        assertNotSame(GOURD, BlockKind.register("test_gourd_other"));
        assertEquals("test_gourd", GOURD.key());
        assertEquals(java.util.Optional.of(GOURD), BlockKind.byKey("test_gourd"));
        assertEquals(java.util.Optional.empty(), BlockKind.byKey("nope"));
    }

    @Test
    @DisplayName("the six Anima ships are registered like anybody else's")
    void theBuiltInsAreOrdinaryRegistrations() {
        for (BlockKind kind : List.of(BlockKind.AIR, BlockKind.LOG, BlockKind.LEAVES,
                BlockKind.WATER, BlockKind.OTHER, BlockKind.UNKNOWN)) {
            assertTrue(BlockKind.all().contains(kind), kind + " is missing from the registry");
            assertEquals(java.util.Optional.of(kind), BlockKind.byKey(kind.key()));
        }
        assertTrue(BlockKind.all().contains(GOURD), "and a consumer's sits beside them");
    }

    @Test
    @DisplayName("a kind Anima never heard of grows into a memory Anima never heard of")
    void aConsumersVocabularyReachesAllTheWayThrough() {
        GrowthRules.register(GOURD, GourdRule.INSTANCE);

        // A little patch on the ground two blocks over, well inside the near field.
        FakeProbe probe = new FakeProbe();
        for (int dx = 0; dx <= 1; dx++) {
            probe.set(2 + dx, FakeProbe.GROUND_Y + 1, 2, GOURD);
        }

        AgentKnowledge knowledge = new AgentKnowledge();
        PoiSensorCore sensor = new PoiSensorCore(knowledge, eyed());
        List<SenseEvent> events = new ArrayList<>();
        for (int tick = 1; tick <= 40; tick++) {
            events.addAll(sensor.tick(HERE, AHEAD, tick, probe));
        }

        List<SenseEvent> noted = events.stream()
                .filter(e -> e.type() == SenseEvent.Type.NOTED)
                .toList();
        assertFalse(noted.isEmpty(), "the patch was never noticed at all: " + events);
        assertTrue(noted.stream().allMatch(e -> e.kind() == GourdRule.PATCH),
                "and what it was noticed AS is the consumer's own kind of place: " + noted);
        assertFalse(knowledge.all(GourdRule.PATCH).isEmpty(), "it is in the store to act on");
    }

    @Test
    @DisplayName("an unclaimed kind grows nothing — the registry is the only way in")
    void aKindWithNoRuleIsJustSomethingThere() {
        FakeProbe probe = new FakeProbe();
        probe.set(2, FakeProbe.GROUND_Y + 1, 2, GOURD);

        AgentKnowledge knowledge = new AgentKnowledge();
        PoiSensorCore sensor = new PoiSensorCore(knowledge, eyed());
        List<SenseEvent> events = new ArrayList<>();
        for (int tick = 1; tick <= 40; tick++) {
            events.addAll(sensor.tick(HERE, AHEAD, tick, probe));
        }

        assertTrue(events.stream().noneMatch(e -> e.type() == SenseEvent.Type.NOTED),
                "declaring a block kind is not declaring that it matters: " + events);
    }

    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 12.0,
                ProfileAspect.PLACES_CONE_DEGREES, 360.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 12.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 0.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_gourd_eyed");
        for (ProfileAspect aspect : ProfileAspect.all()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }
}
