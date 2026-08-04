package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gist tier's own rules, away from any sensor: it merges coarsely, it never argues with a
 * belief, and it is settled by going to look — one way or the other.
 */
class SightingTierTest {

    private static final PoiKind KIND = FakeGrowthRule.THICKET;
    private static final int CAP = 8;

    private final AgentKnowledge knowledge = new AgentKnowledge();

    private Sighting glimpse(int x, int y, int z, long when) {
        return knowledge.glimpse(
                new Sighting(KIND, new Pos(x, y, z), new Pos(0, 64, 0), when,
                        Sighting.Provenance.PASSIVE),
                CAP);
    }

    private PoiMemory belief(int x, int y, int z, long when) {
        Pos anchor = new Pos(x, y, z);
        return knowledge.note(
                new PoiMemory(KIND, anchor, new Region(anchor, anchor), 4, false, when), CAP);
    }

    @Test
    void aWoodIsOneGlimpseNotOnePerTrunk() {
        for (int z = 24; z <= 32; z += 2) {
            for (int x = -4; x <= 4; x += 2) {
                glimpse(x, 68, z, 1);
            }
        }

        assertTrue(knowledge.glimpseCount() <= 2,
                "25 sightings inside one coarse cell must collapse, got "
                        + knowledge.glimpseCount());
        assertEquals(0, knowledge.size(), "and none of it is a belief");
    }

    @Test
    void aFurtherThingIsItsOwnGlimpse() {
        glimpse(0, 68, 30, 1);
        glimpse(0, 68, 60, 1); // well outside the merge radius

        assertEquals(2, knowledge.glimpseCount());
    }

    @Test
    void whatTheyAlreadyKnowIsNotGlimpsedAgain() {
        belief(0, 64, 30, 1);

        assertNull(glimpse(1, 68, 31, 2), "a belief outranks a rumour about the same place");
        assertEquals(0, knowledge.glimpseCount());
    }

    @Test
    void findingTheRealThingSupersedesTheRumour() {
        assertNotNull(glimpse(0, 68, 30, 1));
        assertEquals(1, knowledge.glimpseCount());

        belief(0, 64, 30, 2);
        assertEquals(1, knowledge.supersede(KIND, new Pos(0, 64, 30)));

        assertEquals(0, knowledge.glimpseCount(), "the gist is gone");
        assertEquals(1, knowledge.size(), "and the belief stands in its place");
    }

    @Test
    void lookingAtTheCellAndFindingNothingDisprovesIt() {
        glimpse(0, 68, 30, 1);

        assertEquals(0, knowledge.disprove(0, 29), "a neighbouring empty column proves nothing");
        assertEquals(1, knowledge.glimpseCount());

        assertEquals(1, knowledge.disprove(0, 30), "but its own column does");
        assertEquals(0, knowledge.glimpseCount());
    }

    @Test
    void aRumourTheNEARFIELDCannotSettleSurvivesBeingWalkedPast() {
        // The near field reads one cell per column (the topmost motion-blocking one), and
        // announces "nothing of the sort here" for each. For a kind that does not STAND there
        // (sugar cane has no collision, so the probe reads the sand under it) that is about the
        // wrong cell, and would delete a true belief again every crossing, forever.
        knowledge.glimpse(new Sighting(HIDDEN, new Pos(0, 65, 30), new Pos(0, 64, 0), 1,
                Sighting.Provenance.SURVEY), CAP);
        glimpse(0, 68, 30, 1); // an ordinary surface kind standing on the very same column

        assertEquals(2, knowledge.glimpseCount(), "two rumours, one column");
        assertEquals(1, knowledge.disprove(0, 30),
                "a glance settles the one that stands where a glance looks");
        assertEquals(1, knowledge.glimpseCount(),
                "and leaves the one it never looked at — being walked past told nobody anything");
        assertEquals(HIDDEN, knowledge.glimpses(HIDDEN).iterator().next().kind());
    }

    /** A kind that does not stand at a column's surface, so no glance can speak for it. */
    private static final PoiKind HIDDEN =
            PoiKind.register("test_hidden", 4, "", PoiKind.Settling.DELIBERATE);

    @Test
    void theStalestGoesWhenFull() {
        // Spaced past the merge radius so each is genuinely its own sighting.
        for (int i = 0; i < CAP; i++) {
            glimpse(i * 20, 68, 0, 100 - i); // the last placed is the stalest
        }
        assertEquals(CAP, knowledge.glimpseCount());

        glimpse(-500, 68, 0, 200);

        assertEquals(CAP, knowledge.glimpseCount(), "capacity holds");
        assertTrue(knowledge.glimpses(KIND).stream()
                        .noneMatch(s -> s.at().x() == (CAP - 1) * 20),
                "and it was the stalest that went");
    }

    @Test
    void aGlimpseCarriesHowFarOffItWasMadeOut() {
        Sighting stored = knowledge.glimpse(
                new Sighting(KIND, new Pos(0, 68, 40), new Pos(0, 64, 0), 5,
                        Sighting.Provenance.PASSIVE),
                CAP);

        assertNotNull(stored);
        assertEquals(40, stored.range());
        assertEquals(15, stored.age(20));
    }

    @Test
    void anUnknownProvenanceLabelIsNotAnError() {
        assertEquals(List.of(Sighting.Provenance.PASSIVE, Sighting.Provenance.SURVEY,
                        Sighting.Provenance.TOLD),
                List.of(Sighting.Provenance.values()), "all three are declared up front");
        assertTrue(Sighting.Provenance.byName("HEARD_IN_A_DREAM").isEmpty());
        assertEquals(Sighting.Provenance.TOLD, Sighting.Provenance.byName("TOLD").orElseThrow());
    }
}
