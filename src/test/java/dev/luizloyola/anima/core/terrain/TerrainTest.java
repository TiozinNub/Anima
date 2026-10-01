package dev.luizloyola.anima.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.terrain.Terrain.Kind;
import dev.luizloyola.anima.core.terrain.Terrain.Site;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ground under the trees, judged by rules: trunks come off, a hole in a meadow is still flat, a
 * slope is not, a four-block wall is a cliff, a hillside is steep and a pit's wall is not, a
 * crafting table spoils the ground around it, and nothing unseen is ever usable.
 */
class TerrainTest {

    private static final int SIZE = 41;
    private static final int MID = SIZE / 2;
    private static final int LEVEL = 64;

    private static GroundSample sample(int size, IntBinaryOperator height) {
        GroundSample sample = new GroundSample(0, 0, size, size);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                sample.set(x, z, height.applyAsInt(x, z), 0);
            }
        }
        return sample;
    }

    private static GroundSample level() {
        return sample(SIZE, (x, z) -> LEVEL);
    }

    private static Terrain analyse(GroundSample sample) {
        return Terrain.analyse(sample, TerrainRules.configured());
    }

    /** The shipped defaults, whatever another suite left installed. */
    @BeforeEach
    @AfterEach
    void defaults() {
        Config.reset();
    }

    @Test
    void trunksComeOffTheGround() {
        GroundSample sample = level();
        sample.set(10, 10, LEVEL + 6, 0);
        for (int x = 20; x <= 21; x++) {
            for (int z = 20; z <= 21; z++) {
                sample.set(x, z, LEVEL + 8, 0); // a 2×2 trunk
            }
        }
        Terrain terrain = analyse(sample);

        assertEquals(LEVEL, terrain.ground(10, 10));
        assertEquals(LEVEL, terrain.ground(21, 21));
        assertTrue(terrain.covered(10, 10), "a trunk is something standing");
        assertFalse(terrain.covered(12, 10));
    }

    @Test
    void aOneBlockBumpIsStillGround() {
        GroundSample sample = level();
        sample.set(MID, MID, LEVEL + 1, 0);
        Terrain terrain = analyse(sample);

        assertEquals(LEVEL + 1, terrain.ground(MID, MID));
        assertFalse(terrain.covered(MID, MID));
    }

    @Test
    void aCliffStaysWhereItIs() {
        Terrain terrain = analyse(sample(SIZE, (x, z) -> x < MID ? LEVEL : LEVEL + 6));

        for (int x = 0; x < SIZE; x++) {
            assertEquals(x < MID ? LEVEL : LEVEL + 6, terrain.ground(x, MID), "x = " + x);
        }
    }

    @Test
    void aOneBlockHoleIsFlat() {
        GroundSample sample = level();
        sample.set(MID, MID, LEVEL - 1, 0);
        Terrain terrain = analyse(sample);

        assertEquals(Kind.CLEARING, terrain.kind(MID, MID));
        assertEquals(Kind.CLEARING, terrain.kind(MID + 1, MID));
    }

    @Test
    void aTwoBlockHoleIsStillFlat() {
        GroundSample sample = level();
        sample.set(MID, MID, LEVEL - 2, 0);
        Terrain terrain = analyse(sample);

        assertEquals(Kind.CLEARING, terrain.kind(MID, MID));
    }

    @Test
    void aSlopeIsNotFlat() {
        Terrain terrain = analyse(sample(SIZE, (x, z) -> LEVEL + x / 4));

        assertEquals(Kind.UNEVEN, terrain.kind(MID, MID));
    }

    @Test
    void aWallIsACliffAtItsTop() {
        Terrain terrain = analyse(sample(SIZE, (x, z) -> x < MID ? LEVEL : LEVEL + 4));

        assertEquals(Kind.CLIFF, terrain.kind(MID, MID));
        assertEquals(4, terrain.drop(MID, MID));
        assertNotEquals(Kind.CLIFF, terrain.kind(MID - 1, MID));
        assertEquals(0, terrain.drop(MID - 1, MID));
    }

    @Test
    void aLedgeOfThreeOrLessIsNeitherSteepNorACliff() {
        for (int ledge = 1; ledge <= 3; ledge++) {
            int height = ledge;
            Terrain terrain = analyse(sample(SIZE, (x, z) -> x < MID ? LEVEL : LEVEL + height));
            for (int x = 0; x < SIZE; x++) {
                Kind kind = terrain.kind(x, MID);
                assertNotEquals(Kind.CLIFF, kind, height + "-block ledge, x = " + x);
                assertNotEquals(Kind.STEEP, kind, height + "-block ledge, x = " + x);
            }
        }
    }

    /** A meadow, a 45° flank {@code height} tall starting at {@code foot}, and a plateau. */
    private static GroundSample hill(int foot, int height) {
        return sample(SIZE, (x, z) -> LEVEL + Math.max(0, Math.min(height, x - foot)));
    }

    /** 12 tall: more than the steep height, less than twice it, so its middle is what counts. */
    @Test
    void aHillsideIsSteep() {
        Terrain terrain = analyse(hill(14, 12));

        assertEquals(Kind.STEEP, terrain.kind(20, MID), "the middle of the flank");
        assertNotEquals(Kind.STEEP, terrain.kind(5, MID), "the meadow below");
        assertNotEquals(Kind.STEEP, terrain.kind(36, MID), "the plateau above");
    }

    /** Too short to climb the steep height at all, or climbing it along too narrow a band. */
    @Test
    void aShortRiseIsNotAHillside() {
        for (int height : new int[] {6, 10}) {
            Terrain terrain = analyse(hill(15, height));
            for (int x = 0; x < SIZE; x++) {
                assertNotEquals(Kind.STEEP, terrain.kind(x, MID), height + " tall, x = " + x);
            }
        }
    }

    /** A pit climbs as far as a hillside does, but only back up to the level of the land. */
    @Test
    void aPitWallIsNotAHillside() {
        Terrain terrain = analyse(sample(SIZE,
                (x, z) -> Math.abs(x - MID) <= 3 && Math.abs(z - MID) <= 3 ? LEVEL - 10 : LEVEL));

        assertEquals(Kind.CLIFF, terrain.kind(MID + 4, MID), "the rim");
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                assertNotEquals(Kind.STEEP, terrain.kind(x, z), "(" + x + ", " + z + ")");
            }
        }
    }

    @Test
    void theConfigDecidesWhatIsSteepAndWhatIsACliff() {
        GroundSample wall = sample(SIZE, (x, z) -> x < MID ? LEVEL : LEVEL + 3);
        assertEquals(Kind.STEEP, analyse(hill(12, 16)).kind(20, MID));
        assertNotEquals(Kind.CLIFF, analyse(wall).kind(MID, MID));

        for (Knob knob : new Knob[] {Knob.TERRAIN_STEEP_ANGLE, Knob.TERRAIN_STEEP_HEIGHT,
                Knob.TERRAIN_STEEP_ABOVE_LAND}) {
            double past = knob == Knob.TERRAIN_STEEP_ANGLE ? 60 : 20; // past what the hill offers
            Config.install(Config.get().with(knob, past));
            assertNotEquals(Kind.STEEP, analyse(hill(12, 16)).kind(20, MID), knob.key());
            Config.reset();
        }
        Config.install(Config.get().with(Knob.TERRAIN_CLIFF_HEIGHT, 3));
        assertEquals(Kind.CLIFF, analyse(wall).kind(MID, MID));
    }

    @Test
    void aCliffOverWaterIsACliff() {
        GroundSample sample = level();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                sample.set(x, z, x < MID ? LEVEL : LEVEL + 5, x < MID ? GroundSample.FLUID : 0);
            }
        }
        Terrain terrain = analyse(sample);

        assertEquals(Kind.CLIFF, terrain.kind(MID, MID));
        assertEquals(5, terrain.drop(MID, MID));
    }

    /**
     * A mushroom cap is too wide for the opening, so the reader looks under it and says so. The
     * ground there is flat, and what stood on it is still something to clear. One 17×17 footprint.
     */
    @Test
    void whatTheReaderLookedUnderStillStands() {
        int f = TerrainRules.configured().footprint();
        GroundSample sample = sample(f, (x, z) -> LEVEL);
        for (int x = f / 2 - 3; x <= f / 2 + 3; x++) {
            for (int z = f / 2 - 3; z <= f / 2 + 3; z++) {
                sample.set(x, z, LEVEL, GroundSample.STANDING);
            }
        }
        Terrain terrain = analyse(sample);

        assertTrue(terrain.covered(f / 2, f / 2));
        assertEquals(Kind.FLAT_AREA, terrain.kind(f / 2, f / 2), "flat, but not open");
        assertEquals(1, terrain.sites().get(0).trees(), "one cap, one thing to fell");
    }

    @Test
    void theConfigDecidesWhatIsFlat() {
        Config.install(Config.get().with(Knob.TERRAIN_MAX_SLOPE, 0.3));
        Terrain terrain = analyse(sample(SIZE, (x, z) -> LEVEL + x / 4));

        assertEquals(Kind.CLEARING, terrain.kind(MID, MID), "one in four is flat at max_slope 0.3");
    }

    /**
     * Per cell a step and a slope look alike; across a footprint they do not. One 17×17 footprint,
     * so there is exactly one position to judge.
     */
    @Test
    void aSiteTakesAStepButNotASlope() {
        int f = TerrainRules.configured().footprint();
        Terrain step = analyse(sample(f, (x, z) -> x < f / 2 ? LEVEL : LEVEL + 1));
        Terrain slope = analyse(sample(f, (x, z) -> LEVEL + x / 6));

        assertEquals(1, step.sites().size());
        Site site = step.sites().get(0);
        assertTrue(site.tilt() < TerrainRules.configured().maxTilt(), "tilt " + site.tilt());
        assertTrue(slope.sites().isEmpty(), "a 1-in-6 slope is too tilted to build on");
    }

    @Test
    void usedGroundSpoilsItsMargin() {
        // Off-centre: a used square in the middle of this meadow would leave no 17×17 anywhere.
        int table = 10;
        GroundSample sample = level();
        sample.set(table, table, LEVEL + 1, GroundSample.USED); // a crafting table
        Terrain terrain = analyse(sample);
        int margin = TerrainRules.configured().usedMargin();

        assertEquals(Kind.USED, terrain.kind(table, table));
        assertEquals(Kind.USED, terrain.kind(table + margin, table));
        assertTrue(terrain.kind(table + margin + 1, table) != Kind.USED);
        assertFalse(terrain.sites().isEmpty(), "the rest of the meadow is still usable");
        for (Site site : terrain.sites()) {
            int half = site.size() / 2;
            boolean overlaps = Math.abs(site.x() - table) <= half + margin
                    && Math.abs(site.z() - table) <= half + margin;
            assertFalse(overlaps, "site at " + site.x() + ", " + site.z() + " covers used ground");
        }
    }

    @Test
    void fluidIsNeverFlat() {
        GroundSample sample = level();
        sample.set(MID, MID, LEVEL, GroundSample.FLUID);
        Terrain terrain = analyse(sample);

        assertEquals(Kind.FLUID, terrain.kind(MID, MID));
    }

    @Test
    void aClearingNeedsItsSquare() {
        GroundSample sample = level();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                boolean strip = x >= 5 && x <= 9;              // 5 wide: too narrow
                boolean patch = x >= 25 && x <= 35 && z >= 15 && z <= 25; // 11×11: wide enough
                sample.set(x, z, LEVEL, strip || patch ? 0 : GroundSample.CANOPY);
            }
        }
        Terrain terrain = analyse(sample);

        assertEquals(Kind.FLAT_AREA, terrain.kind(7, MID), "flat, but no 9×9 of open ground");
        assertEquals(Kind.CLEARING, terrain.kind(30, 20));
    }

    @Test
    void unknownGroundIsNeverUsable() {
        GroundSample sample = new GroundSample(0, 0, SIZE, SIZE);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (x < MID) {
                    sample.set(x, z, LEVEL, 0); // the other half was never loaded
                }
            }
        }
        Terrain terrain = analyse(sample);

        assertEquals(Kind.UNKNOWN, terrain.kind(MID + 5, MID));
        assertEquals(GroundSample.UNKNOWN, terrain.ground(MID + 5, MID));
        for (Site site : terrain.sites()) {
            assertTrue(site.x() + site.size() / 2 < MID, "site at x " + site.x() + " reaches unseen ground");
        }
    }

    @Test
    void everyAllowedFootprintIsAskedByItsCentre() {
        int f = TerrainRules.configured().footprint();
        GroundSample sample = level();
        sample.set(MID, MID, LEVEL - 1, GroundSample.FLUID); // one column of water in the middle
        Terrain terrain = analyse(sample);

        assertFalse(terrain.allowed(MID, MID), "a footprint over water");
        assertFalse(terrain.allowed(MID - f / 2, MID), "its edge on the water");
        assertTrue(terrain.allowed(MID - f / 2 - 1, MID), "just clear of it");
        assertFalse(terrain.allowed(f / 2 - 1, f / 2), "would leave the read");
        assertTrue(terrain.allowed(f / 2, f / 2));
        assertTrue(terrain.site(MID, MID).isEmpty());
        for (Site site : terrain.sites()) {
            assertTrue(terrain.allowed(site.x(), site.z()));
            assertEquals(site, terrain.site(site.x(), site.z()).orElseThrow());
        }
    }

    @Test
    void aTiltedFootprintIsNotAllowed() {
        int f = TerrainRules.configured().footprint();
        Terrain slope = analyse(sample(f, (x, z) -> LEVEL + x / 6));

        assertFalse(slope.allowed(f / 2, f / 2));
        assertTrue(analyse(sample(f, (x, z) -> LEVEL)).allowed(f / 2, f / 2));
    }

    @Test
    void aRectangleIsAskedByItsCorner() {
        GroundSample sample = level();
        sample.set(MID, MID, LEVEL - 1, GroundSample.FLUID);
        Terrain.Rects house = analyse(sample).rects(9, 10, Double.POSITIVE_INFINITY);

        assertEquals(9, house.width());
        assertEquals(10, house.depth());
        assertFalse(house.allowed(MID - 8, MID - 9), "its south-east corner on the water");
        assertTrue(house.allowed(MID - 9, MID - 9), "one column west of it");
        assertTrue(house.allowed(MID - 8, MID - 10), "one row north of it");
        assertFalse(house.allowed(SIZE - 8, 0), "would leave the read");
        assertTrue(house.allowed(SIZE - 9, SIZE - 10));
        assertTrue(house.fit(MID - 8, MID - 9).isEmpty());
        assertTrue(Double.isNaN(house.estimate(MID - 8, MID - 9)));
    }

    @Test
    void aRectanglesTiltIsMeasuredAlongBothOfItsSides() {
        // Were the two sides' sums swapped, a 9-wide, 10-deep rise of one a block would read
        // anything but one.
        Terrain alongX = analyse(sample(SIZE, (x, z) -> LEVEL + x));
        Terrain alongZ = analyse(sample(SIZE, (x, z) -> LEVEL + z));

        assertEquals(1.0, alongX.rects(9, 10, Double.POSITIVE_INFINITY).fit(5, 5).orElseThrow().tilt(), 1e-9);
        assertEquals(1.0, alongZ.rects(9, 10, Double.POSITIVE_INFINITY).fit(5, 5).orElseThrow().tilt(), 1e-9);
        assertFalse(alongX.rects(9, 10, 0.5).allowed(5, 5), "past the tilt it was asked for");
    }

    @Test
    void aRectanglesLevellingIsCountedBlockByBlock() {
        GroundSample sample = level();
        for (int x = 12; x < 15; x++) {
            for (int z = 12; z < 15; z++) {
                sample.set(x, z, LEVEL + 2, 0); // a 3×3 mound, two high
            }
        }
        Terrain.Rects house = analyse(sample).rects(9, 10, Double.POSITIVE_INFINITY);
        Terrain.Rect fit = house.fit(10, 10).orElseThrow();

        assertEquals(LEVEL + 0.2, fit.y(), 1e-9, "18 blocks over 90 columns");
        assertEquals(18, fit.levelling(), "the mound cut down to the meadow");
        assertTrue(house.estimate(10, 10) >= fit.levelling(), "the estimate never undercounts");
        assertEquals(0, house.fit(25, 25).orElseThrow().levelling());
    }

    @Test
    void aRectangleCountsATrunkOnce() {
        GroundSample sample = level();
        for (int x = 20; x <= 21; x++) {
            for (int z = 20; z <= 21; z++) {
                sample.set(x, z, LEVEL + 8, 0); // a 2×2 trunk
            }
        }
        Terrain.Rect fit = analyse(sample).rects(9, 10, Double.POSITIVE_INFINITY).fit(16, 16).orElseThrow();

        assertEquals(1, fit.trees());
        assertEquals(0, fit.levelling(), "and reads the ground under it");
    }
}
