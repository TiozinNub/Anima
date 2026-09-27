package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Doors — {@link CellType#DOOR}: crossed along one axis, and only once swung if a panel stands
 * across it. They read as walls until 2026-09-26, so a house was a box with no way in.
 *
 * <p>Every world is a wall along {@code x = 3} with a doorway in it, and a route that has to go
 * through it east–west.
 */
class PathfinderDoorTest {

    private static final MoveCapabilities HANDS = TestBodies.BIPED;
    private static final MoveCapabilities PAWS = new MoveCapabilities(1.8, 1, 3, 3, true, 36, false, true);

    private static final int ALL = NavGrid.NORTH | NavGrid.SOUTH | NavGrid.WEST | NavGrid.EAST;
    /** A door facing east, open: its panel on the north face; shut again, on the west face. */
    private static final int OPEN = Doorway.of(NavGrid.NORTH, false, NavGrid.WEST, false, ALL, true);
    /** The same door shut: its panel across the corridor, on the west face, until a hand swings it. */
    private static final int SHUT = Doorway.of(NavGrid.WEST, false, NavGrid.NORTH, false, ALL, true);
    /** An iron door, shut, with nothing beside it to open it: no hand swings it. */
    private static final int IRON = Doorway.of(NavGrid.WEST, false, NavGrid.NORTH, false, 0, false);
    /** A shut gate: its bar crosses the middle, and the faces it spans, until it is swung open. */
    private static final int GATE = Doorway.of(NavGrid.NORTH | NavGrid.SOUTH, true, 0, false, ALL, true);
    /** An iron door with a button on its west side only: opened from there, and only from there. */
    private static final int IRON_BUTTON_WEST = Doorway.of(NavGrid.WEST, false, NavGrid.NORTH, false,
            NavGrid.WEST, false);
    /** An iron door with its button on the east side: nobody coming from the west can open it. */
    private static final int IRON_BUTTON_EAST = Doorway.of(NavGrid.WEST, false, NavGrid.NORTH, false,
            NavGrid.EAST, false);

    private static AsciiWorld wallWithDoorway(int passages, int tall) {
        return AsciiWorld.of(
                "111#111",
                "111#111",
                "1111111",
                "111#111",
                "111#111").door(3, 1, 2, 3, tall, 2, passages);
    }

    private static Path find(NavGrid world, MoveCapabilities body, int sx, int sz, int gx, int gz) {
        Path path = Pathfinder.find(world, PathRequest.of(sx, 1, sz, gx, 1, gz, body));
        assertKeepsItsOwnContract(world, path, sx, sz, body);
        return path;
    }

    /** Every edge the search emits must already meet what the follower will re-check it for. */
    private static void assertKeepsItsOwnContract(NavGrid world, Path path, int sx, int sz,
                                                  MoveCapabilities body) {
        Waypoint from = new Waypoint(sx, 1, sz, MoveType.WALK);
        for (Waypoint to : path.waypoints()) {
            for (CellNeed need : PathIntegrity.edgeNeeds(from, to, body)) {
                assertTrue(NavGrids.satisfies(world, need), need + " on the edge into " + to);
            }
            from = to;
        }
    }

    private static boolean passes(Path path, int x, int z) {
        return path.waypoints().stream().anyMatch(w -> w.x() == x && w.z() == z);
    }

    @Test
    void anOpenDoorIsWalkedThrough() {
        Path path = find(wallWithDoorway(OPEN, 2), HANDS, 0, 2, 6, 2);
        assertTrue(path.reachedGoal());
        assertTrue(passes(path, 3, 2), "through the doorway, a waypoint of its own");

        AsciiWorld walled = wallWithDoorway(OPEN, 2).fill(3, 1, 2, 3, 2, 2, CellType.OBSTACLE);
        assertFalse(find(walled, HANDS, 0, 2, 6, 2).reachedGoal(),
                "the same doorway as a wall must still block — otherwise this proves nothing");
    }

    @Test
    void anOpenDoorNeedsNoHand() {
        assertTrue(find(wallWithDoorway(OPEN, 2), PAWS, 0, 2, 6, 2).reachedGoal());
    }

    @Test
    void aShutDoorIsAWayThroughForAHandAndAWallWithoutOne() {
        assertTrue(find(wallWithDoorway(SHUT, 2), HANDS, 0, 2, 6, 2).reachedGoal());
        assertFalse(find(wallWithDoorway(SHUT, 2), PAWS, 0, 2, 6, 2).reachedGoal());
    }

    @Test
    void aHandSwingsWoodAndGatesButNotIron() {
        assertTrue(Doorway.swings(SHUT));
        assertTrue(Doorway.swings(OPEN));
        assertTrue(Doorway.swings(GATE));
        assertFalse(Doorway.swings(IRON));
    }

    @Test
    void anIronDoorIsAWallToEverybody() {
        assertFalse(find(wallWithDoorway(IRON, 2), HANDS, 0, 2, 6, 2).reachedGoal());
    }

    @Test
    void aShutGateIsAWayThroughForAHandToo() {
        assertTrue(find(wallWithDoorway(GATE, 1), HANDS, 0, 2, 6, 2).reachedGoal());
        assertFalse(find(wallWithDoorway(GATE, 1), PAWS, 0, 2, 6, 2).reachedGoal());
    }

    @Test
    void aDoorIsEnteredAndLeftStraightAlongItsAxis() {
        // The goal lies diagonally beyond the wall, so a route free to cut corners would go into the
        // doorway on a slant. A door is crossed beside its panel, never through a corner of it.
        Path path = find(wallWithDoorway(SHUT, 2), HANDS, 0, 0, 6, 4);
        assertTrue(path.reachedGoal());
        List<Waypoint> ways = path.waypoints();
        int door = -1;
        for (int i = 0; i < ways.size(); i++) {
            if (ways.get(i).x() == 3 && ways.get(i).z() == 2) door = i;
        }
        assertTrue(door > 0 && door < ways.size() - 1, "the doorway is a waypoint mid-route");
        assertEquals(List.of(2, 2), List.of(ways.get(door - 1).x(), ways.get(door - 1).z()));
        assertEquals(List.of(4, 2), List.of(ways.get(door + 1).x(), ways.get(door + 1).z()));
    }

    @Test
    void anOpenDoorwayBeatsAShutOneALittleNearer() {
        // Two doorways side by side. Straight through the shut one is six steps and a swing; the
        // open one is a jog of half a step either side. The swing is the difference.
        AsciiWorld two = AsciiWorld.of(
                        "111#111",
                        "111#111",
                        "1111111",
                        "1111111",
                        "111#111")
                .door(3, 1, 2, 3, 2, 2, SHUT)
                .door(3, 1, 3, 3, 2, 3, OPEN);
        Path path = find(two, HANDS, 0, 2, 6, 2);
        assertTrue(path.reachedGoal());
        assertTrue(passes(path, 3, 3), "the open door, with nothing to swing: " + path.waypoints());
        assertFalse(passes(path, 3, 2));
    }

    @Test
    void aDoorIsNeverEnteredOrLeftOnASlant() {
        // No wall to refuse a corner cut: only the door's own rule keeps a slant out of it.
        AsciiWorld field = AsciiWorld.of(
                "11111",
                "11111",
                "11111").door(2, 1, 1, 2, 2, 1, OPEN);
        Path in = find(field, HANDS, 1, 0, 2, 1);
        assertEquals(List.of(new Waypoint(1, 1, 1, MoveType.WALK), new Waypoint(2, 1, 1, MoveType.WALK)),
                in.waypoints(), "stepped round to come in straight, by a face the panel leaves free");
        Path out = Pathfinder.find(field, PathRequest.of(2, 1, 1, 3, 1, 2, HANDS));
        Waypoint first = out.waypoints().get(0);
        assertEquals(1, Math.abs(first.x() - 2) + Math.abs(first.z() - 1), "and out straight: " + first);
    }

    /** A door in the corner of an L: in from the west, out to the south. */
    private static AsciiWorld corner(int door) {
        return AsciiWorld.of(
                        "####",
                        "#11#",
                        "##1#",
                        "##1#",
                        "####")
                .door(2, 1, 1, 2, 2, 1, door);
    }

    @Test
    void aDoorwayIsTurnedInWhereThePanelLeavesBothFacesFree() {
        // Open, the panel is on the north face: west and south are both free.
        assertTrue(find(corner(OPEN), PAWS, 1, 1, 2, 3).reachedGoal());
    }

    @Test
    void aDoorwayIsTurnedInByAHandThatSwingsItAgainInside() {
        // Panel on the south face as it stands and on the west face swung: no one state lets the
        // body in from the west and out to the south, so it comes in, then swings it.
        int southThenWest = Doorway.of(NavGrid.SOUTH, false, NavGrid.WEST, false, ALL, true);
        assertTrue(find(corner(southThenWest), HANDS, 1, 1, 2, 3).reachedGoal());
        int ironSouthThenWest = Doorway.of(NavGrid.SOUTH, false, NavGrid.WEST, false, NavGrid.WEST, false);
        assertFalse(find(corner(ironSouthThenWest), HANDS, 1, 1, 2, 3).reachedGoal(),
                "a button outside cannot be pressed from the middle of the doorway");
    }

    @Test
    void anIronDoorOpensFromTheSideItsButtonIsOn() {
        assertTrue(find(wallWithDoorway(IRON_BUTTON_WEST, 2), HANDS, 0, 2, 6, 2).reachedGoal());
        assertFalse(find(wallWithDoorway(IRON_BUTTON_EAST, 2), HANDS, 0, 2, 6, 2).reachedGoal(),
                "the button is on the far side");
        assertTrue(find(wallWithDoorway(IRON_BUTTON_EAST, 2), HANDS, 6, 2, 0, 2).reachedGoal(),
                "and from the far side it opens");
        assertFalse(find(wallWithDoorway(IRON_BUTTON_WEST, 2), PAWS, 0, 2, 6, 2).reachedGoal());
    }

    @Test
    void aClosedHouseIsSealedOnlyToABodyWithoutAHand() {
        // A room with its door shut, onto a walled yard. To paws the room is the whole world; to a
        // hand the door is a way out, and the region grows by the doorway and the yard.
        AsciiWorld house = AsciiWorld.of(
                        "#########",
                        "#111#111#",
                        "#1111111#",
                        "#111#111#",
                        "#########")
                .door(4, 1, 2, 4, 2, 2, SHUT);
        Path shut = Pathfinder.find(house, PathRequest.of(2, 1, 2, 40, 1, 40, PAWS));
        Path out = Pathfinder.find(house, PathRequest.of(2, 1, 2, 40, 1, 40, HANDS));
        assertTrue(shut.sealed());
        assertEquals(9, shut.reachableCells(), "the room and nothing else");
        assertEquals(19, out.reachableCells(), "the room, the doorway and the yard");
    }
}
