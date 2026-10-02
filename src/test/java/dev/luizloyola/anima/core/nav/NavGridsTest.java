package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavGridsTest {

    private static boolean nearDrop(AsciiWorld world, int x, int y, int z) {
        return NavGrids.isNearDeepDrop(world, TestBodies.BIPED.maxDrop(), x, y, z);
    }

    @Test
    void openGroundIsNotAnEdge() {
        AsciiWorld world = AsciiWorld.of(
                "111",
                "111",
                "111");
        assertFalse(nearDrop(world, 1, 1, 1));
    }

    @Test
    void besideABottomlessHoleIsAnEdge() {
        assertTrue(nearDrop(AsciiWorld.of("11 "), 1, 1, 0));
    }

    @Test
    void aChasmDeeperThanMaxDropIsAnEdge() {
        // 5-high plateau beside height-1 ground: a 4-block fall, one more than maxDrop.
        assertTrue(nearDrop(AsciiWorld.of("551"), 1, 5, 0));
    }

    @Test
    void aSurvivableDropIsNotAnEdge() {
        // 4-high plateau beside height-1 ground: a 3-block drop is an everyday move.
        assertFalse(nearDrop(AsciiWorld.of("441"), 1, 4, 0));
    }

    @Test
    void besideLavaIsAnEdge() {
        assertTrue(nearDrop(AsciiWorld.of("11L"), 1, 1, 0));
    }

    @Test
    void besideDeepWaterIsAnEdge() {
        assertTrue(nearDrop(AsciiWorld.of("11W"), 1, 1, 0));
    }

    /** A puddle is waded, not skirted — see the depth test in {@code NavGrids.isNearDeepDrop}. */
    @Test
    void besideAPuddleIsNot() {
        assertFalse(nearDrop(AsciiWorld.of("11w"), 1, 1, 0));
    }

    @Test
    void besideAWallIsNotAnEdge() {
        assertFalse(nearDrop(AsciiWorld.of("11#"), 1, 1, 0));
    }

    /**
     * The follower asks this of the live world, so it must say what the planner assumes: a puddle is
     * floor. A follower with its own copy of the rule refused it and re-planned every 20 ticks.
     */
    @Test
    void aPuddleIsFootingAndOpenWaterIsNot() {
        AsciiWorld world = AsciiWorld.of("1wW");
        assertTrue(NavGrids.satisfies(world, new CellNeed(0, 1, 0, CellNeed.Need.FOOTING)));
        assertTrue(NavGrids.satisfies(world, new CellNeed(1, 0, 0, CellNeed.Need.FOOTING)));
        assertFalse(NavGrids.satisfies(world, new CellNeed(2, 0, 0, CellNeed.Need.FOOTING)));
    }

    @Test
    void aDoorwayAndTheFootOfALadderAreStoodInAndARungIsHeld() {
        AsciiWorld world = AsciiWorld.of("11")
                .door(0, 1, 0, 0, 2, 0, Doorway.of(NavGrid.NORTH, false, NavGrid.WEST, false, 15, true))
                .climb(1, 1, 0, 1, 4, 0);
        assertTrue(NavGrids.satisfies(world, new CellNeed(0, 1, 0, CellNeed.Need.FOOTING)));
        assertTrue(NavGrids.satisfies(world, new CellNeed(0, 2, 0, CellNeed.Need.CLEAR)));
        assertTrue(NavGrids.satisfies(world, new CellNeed(1, 1, 0, CellNeed.Need.FOOTING)));
        assertFalse(NavGrids.satisfies(world, new CellNeed(1, 3, 0, CellNeed.Need.FOOTING)),
                "halfway up there is nothing to stand on");
        assertTrue(NavGrids.satisfies(world, new CellNeed(1, 3, 0, CellNeed.Need.HOLD)));
        assertFalse(NavGrids.satisfies(world, new CellNeed(1, 6, 0, CellNeed.Need.HOLD)),
                "and past the top, nothing to hold");
    }

    private static boolean besideHarm(NavGrid world, int x, int y, int z, boolean drops) {
        return NavGrids.besideHarm(world, TestBodies.BIPED, x, y, z, drops);
    }

    @Test
    void lavaAtTheFeetIsHarmThoughNoStepLandsInIt() {
        AsciiWorld world = AsciiWorld.of("111", "111", "111");
        world.fill(2, 1, 2, 2, 1, 2, CellType.DANGER);
        assertFalse(nearDrop(world, 1, 1, 1), "the throttle never looked there");
        assertTrue(besideHarm(world, 1, 1, 1, false), "diagonal, at the feet");
    }

    @Test
    void harmAtTheHeadCountsAndAboveItDoesNot() {
        AsciiWorld head = AsciiWorld.of("111", "111", "111");
        head.fill(0, 2, 1, 0, 2, 1, CellType.DANGER);
        assertTrue(besideHarm(head, 1, 1, 1, false));
        AsciiWorld above = AsciiWorld.of("111", "111", "111");
        above.fill(0, 3, 1, 0, 3, 1, CellType.DANGER);
        assertFalse(besideHarm(above, 1, 1, 1, false));
    }

    @Test
    void aDropThatHurtsCountsOnlyWhenAsked() {
        AsciiWorld corner = AsciiWorld.of("55 ", "555", "555");
        assertFalse(nearDrop(corner, 1, 5, 1), "diagonal: no cardinal step lands in it");
        assertTrue(besideHarm(corner, 1, 5, 1, true));
        assertFalse(besideHarm(corner, 1, 5, 1, false));
        assertFalse(besideHarm(AsciiWorld.of("442", "444", "444"), 1, 4, 1, true),
                "two down is an everyday move");
    }
}
