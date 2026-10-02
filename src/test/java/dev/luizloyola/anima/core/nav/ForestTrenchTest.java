package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The forest case of 2026-10-02: a settler east of a ravine nine wide and twenty deep, sent to a
 * strip of ground on its west rim, stranded 268 times in ten minutes. A lake closes it to the north
 * and it runs on past the walk's capture to the south. The strip's walks could not build, so the
 * eight blocks of dirt she came to carry were never tried, and nine were needed. The capture is the
 * walk's own box, cut at the live capture's floor (y 55).
 */
class ForestTrenchTest {

    private static CapturedWorld world;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = ForestTrenchTest.class.getResourceAsStream("/nav/forest-trench.txt")) {
            assertNotNull(in, "missing fixture /nav/forest-trench.txt");
            world = CapturedWorld.parse(CapturedWorld.lines(in));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Where she stood, east of the ravine, and the strip's middle on its west rim. */
    private static Path walk(int x, int y, int z, MoveCapabilities body) {
        return Pathfinder.find(world, PathRequest.of(x, y, z, -120, 69, -306, body.withScaling(true)));
    }

    /** From the east rim, where every walk ended: no way round inside the capture. */
    private static Path fromTheRim(MoveCapabilities body) {
        return walk(-108, 69, -304, body);
    }

    @Test
    void aWalkThatMayOnlyScaleIsStrandedOnTheRim() {
        Path path = fromTheRim(TestBodies.BIPED);
        assertTrue(path.isEmpty(), "the lake to the north, the ravine past the capture to the south");
        assertEquals(0, path.blocksNeeded(), "it may not build, so it is not told to fetch");
    }

    @Test
    void aWalkThatMayBuildButCarriesNothingIsToldItNeedsNine() {
        Path path = fromTheRim(TestBodies.BIPED.building(0));
        assertFalse(path.reachedGoal());
        assertEquals(9, path.blocksNeeded());
    }

    @Test
    void withNineItDecksStraightAcross() {
        Path path = fromTheRim(TestBodies.BIPED.building(9));
        assertTrue(path.reachedGoal());
        assertEquals(9, path.laid());
        assertTrue(path.waypoints().stream().filter(w -> w.move() == MoveType.BRIDGE)
                .allMatch(w -> w.z() == -304 && w.y() == 69), "a straight deck at the rim");
    }

    @Test
    void fromWhereSheStoodTheFirstRouteOnlyReachesTheRim() {
        Path path = walk(-107, 69, -300, TestBodies.BIPED.building(0));
        assertFalse(path.reachedGoal());
        Waypoint last = path.last();
        assertEquals(List.of(-108, 69, -304), List.of(last.x(), last.y(), last.z()));
        assertEquals(0, path.blocksNeeded(), "not stranded yet: the retry from the rim is");
    }
}
