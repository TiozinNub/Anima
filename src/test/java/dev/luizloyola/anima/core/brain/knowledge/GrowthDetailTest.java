package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@link PoiMemory} is built through TWO different objects: the fresh-scan path
 * ({@link GrownRegion#toMemory}) and the {@link PlaceIndex} fast path a re-seen crowd takes
 * ({@link PlaceIndex.Place#toMemory}). Teaching only one lets a giant re-seen from another corner
 * re-anchor by a block, compare its detail against the other carrier's {@code ""}, and split one
 * tree into two memories instead of merging.
 */
class GrowthDetailTest {

    private static final String OAK = "minecraft:oak_log";

    @AfterEach
    void restoreDefaults() {
        Config.reset();
    }

    @Test
    @DisplayName("a species survives both ways a thing is remembered")
    void aSpeciesSurvivesBothWaysAThingIsRemembered() {
        GrowthRule.Evaluation eval = new GrowthRule.Evaluation(
                List.of(new Pos(0, 64, 0)), 5, Map.of(new Pos(0, 64, 0), BlockKind.LOG), OAK);
        assertEquals(OAK, eval.detail());

        GrownRegion.Part part = new GrownRegion.Part(List.of(new Pos(0, 64, 0)),
                Region.of(new Pos(0, 64, 0)), 5, Map.of(new Pos(0, 64, 0), BlockKind.LOG),
                true, OAK);
        assertEquals(OAK, part.detail());
    }

    @Test
    @DisplayName("a thing with nothing to qualify it still has no detail")
    void athingWithNothingToQualifyItStillHasNoDetail() {
        GrowthRule.Evaluation eval = new GrowthRule.Evaluation(
                List.of(new Pos(0, 64, 0)), 5, Map.of(new Pos(0, 64, 0), BlockKind.LOG));
        assertEquals("", eval.detail(), "water and workbenches qualify nothing, and pay nothing");
    }

    /** A 2x2 trunk cross-section — a giant whose corners are more than one approach apart. */
    private static GrownRegion.Part giantBase() {
        List<Pos> corners = List.of(new Pos(10, 64, 10), new Pos(11, 64, 10),
                new Pos(10, 64, 11), new Pos(11, 64, 11));
        Map<Pos, BlockKind> blocks = new LinkedHashMap<>();
        for (Pos cell : corners) {
            blocks.put(cell, BlockKind.LOG);
        }
        Region bounds = Region.of(corners.get(0)).including(corners.get(3));
        return new GrownRegion.Part(corners, bounds, 4, blocks, true, OAK);
    }

    @Test
    @DisplayName("the same tree, re-seen through the OTHER carrier, still merges with itself")
    void reSeenThroughTheOtherCarrierStillMerges() {
        GrownRegion.Part part = giantBase();
        GrownRegion region = new GrownRegion(TestPois.TREE, false, part.blocks(), List.of(part));
        AgentKnowledge knowledge = new AgentKnowledge();

        // First meeting: the fresh-scan path, approached from the (10,10) corner.
        PoiMemory first = region.toMemory(part, new Pos(10, 64, 5), 100L);
        assertEquals(OAK, first.detail());
        knowledge.note(first, 64);

        // Re-seen from the opposite corner, through the OTHER carrier: PlaceIndex's fast path —
        // the anchor moves one block, exactly what a re-approached 2x2 giant does.
        Config.install(Config.get().with(Knob.PLACE_INDEX_CELLS, 1024));
        PlaceIndex places = new PlaceIndex();
        places.putAll(region);
        PlaceIndex.Place found = places.at(TestPois.TREE, new Pos(11, 64, 11));
        PoiMemory second = found.toMemory(new Pos(11, 64, 16), 200L);
        assertEquals(OAK, second.detail());
        knowledge.note(second, 64);

        assertEquals(1, knowledge.size(),
                "a half-taught carrier compares \"\" against \"" + OAK + "\", refuses to merge, "
                        + "and one tree becomes two memories");
    }
}
