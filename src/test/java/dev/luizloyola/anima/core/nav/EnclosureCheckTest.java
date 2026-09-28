package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Holes;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Openness;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The enclosure check (spec: {@code 2026-09-28-shelter-design.md}): four verdicts by how the
 * weakest way out is closed, the doors that would close it, holes too small for this body, and a
 * roof.
 *
 * <p>Every world is a field {@value #SIZE} across with a house in it: walls on
 * {@value #LO}..{@value #HI}, a front door in the north wall at x = 12, the body standing inside at
 * (12, 1, 12). The grid is a window with real edges, as a capture is, so open ground reaches the rim.
 */
class EnclosureCheckTest {

    private static final int SIZE = 25;
    private static final int LO = 8;
    private static final int HI = 16;
    private static final int DOOR_X = 12;
    private static final int BUDGET = 8192;

    private static final MoveCapabilities HANDS = TestBodies.BIPED;
    private static final MoveCapabilities PAWS =
            new MoveCapabilities(1.8, 1, 3, 3, true, 36, false, true);

    private static final int ALL = NavGrid.NORTH | NavGrid.SOUTH | NavGrid.WEST | NavGrid.EAST;
    /** A door in a north or south wall, shut: its panel across the way through. */
    private static final int SHUT_NS = Doorway.of(NavGrid.NORTH, false, NavGrid.WEST, false, ALL, true);
    /** The same door swung open, its panel against the west face. */
    private static final int OPEN_NS = Doorway.of(NavGrid.WEST, false, NavGrid.NORTH, false, ALL, true);
    /** A door in an east or west wall, open. */
    private static final int OPEN_EW = Doorway.of(NavGrid.NORTH, false, NavGrid.WEST, false, ALL, true);
    /** A door in an east or west wall, shut. */
    private static final int SHUT_EW = Doorway.of(NavGrid.WEST, false, NavGrid.NORTH, false, ALL, true);

    // ── the four verdicts ────────────────────────────────────────────────────────────────────

    @Test
    void aClosedHouseIsOpenableAndAShelter() {
        Enclosure e = check(house(SHUT_NS).roof());
        assertEquals(Openness.OPENABLE, e.openness());
        assertTrue(e.roofed());
        assertTrue(e.shelter());
        assertEquals(Holes.NONE, e.holes());
        assertEquals(50, e.space().size(),
                "the seven-by-seven floor, and the doorway: the panel is on its outer face");
        assertTrue(e.contains(new Pos(12, 1, 12)));
        assertEquals(List.of(new Pos(DOOR_X, 1, LO)), e.doors(), "the front door is watched");
    }

    @Test
    void theSameHouseWithItsDoorOpenIsCloseableByThatDoor() {
        Enclosure e = check(house(OPEN_NS).roof());
        assertEquals(Openness.CLOSEABLE, e.openness());
        assertEquals(List.of(new Pos(DOOR_X, 1, LO)), e.doorsToShut());
        assertEquals(49, e.space().size(), "the space as it would be with the door shut");
        assertTrue(e.roofed());
        assertFalse(e.shelter(), "a shelter only once the door is shut");
    }

    @Test
    void aGapABodyFitsThroughIsOpen() {
        Enclosure e = check(house(SHUT_NS).roof().gap(HI, 12, 2));
        assertEquals(Openness.OPEN, e.openness());
        assertEquals(Holes.PASSABLE, e.holes());
        assertTrue(e.space().isEmpty());
        assertFalse(e.shelter());

        assertEquals(Openness.OPENABLE, check(house(SHUT_NS).roof()).openness(),
                "the same house without the gap must still be shut — otherwise this proves nothing");
    }

    @Test
    void aSealedBoxIsClosed() {
        Enclosure e = check(house(-1).roof());
        assertEquals(Openness.CLOSED, e.openness());
        assertTrue(e.shelter());
        assertTrue(e.doors().isEmpty());
    }

    @Test
    void aPitTheBodyCannotClimbOutOfIsClosedButNoShelter() {
        StringBuilder field = new StringBuilder();
        String[] rows = new String[SIZE];
        for (int z = 0; z < SIZE; z++) {
            field.setLength(0);
            for (int x = 0; x < SIZE; x++) {
                field.append(x >= 11 && x <= 13 && z >= 11 && z <= 13 ? '1' : '4');
            }
            rows[z] = field.toString();
        }
        Enclosure e = check(window(AsciiWorld.of(rows)));
        assertEquals(Openness.CLOSED, e.openness(), "three blocks up on every side");
        assertFalse(e.roofed(), "a zombie falls in, a spider climbs in");
        assertFalse(e.shelter());
    }

    @Test
    void anUnroofedHouseIsShutButNoShelter() {
        Enclosure e = check(house(SHUT_NS));
        assertEquals(Openness.OPENABLE, e.openness());
        assertFalse(e.roofed());
        assertFalse(e.shelter());
    }

    @Test
    void aBodyWithoutHandsGetsTheSameVerdict() {
        assertEquals(Openness.OPENABLE, EnclosureCheck.run(house(SHUT_NS).roof().grid(),
                12, 1, 12, PAWS, BUDGET, 0L).openness());
    }

    @Test
    void aSpaceWiderThanTheCaptureIsOpen() {
        Enclosure e = EnclosureCheck.run(window(house(SHUT_NS).roof().world, 0, 13),
                12, 1, 12, HANDS, BUDGET, 0L);
        assertEquals(Openness.OPEN, e.openness(),
                "the capture's edge runs through the house, and past it could be anything");
    }

    // ── which doors to shut ──────────────────────────────────────────────────────────────────

    @Test
    void aDoorIntoAClosedCupboardIsLeftOpen() {
        // A cupboard beyond the east wall: one cell, walled round and roofed, through an open door.
        House h = house(OPEN_NS).roof();
        h.world.fill(HI + 2, 1, 11, HI + 2, 9, 13, CellType.GROUND)
                .fill(HI + 1, 1, 11, HI + 1, 9, 11, CellType.GROUND)
                .fill(HI + 1, 1, 13, HI + 1, 9, 13, CellType.GROUND)
                .fill(HI + 1, 3, 12, HI + 1, 9, 12, CellType.GROUND);
        h.gap(HI, 12, 2).world.door(HI, 1, 12, HI, 2, 12, OPEN_EW);
        Enclosure e = check(h);
        assertEquals(Openness.CLOSEABLE, e.openness());
        assertEquals(List.of(new Pos(DOOR_X, 1, LO)), e.doorsToShut(),
                "the front door leads out; the cupboard door leads nowhere");
        assertEquals(49 + 2, e.space().size(), "the cupboard and its doorway are on this side");
    }

    @Test
    void theDoorToShutIsTheNearestOneBetweenTheBodyAndTheWayOut() {
        // An inner wall at x = 12 splits the house; the body stands in the east half, the front
        // door is in the west half, and the inner door is open.
        House h = house(OPEN_NS, 10).roof();
        h.world.fill(12, 1, LO + 1, 12, 9, HI - 1, CellType.GROUND)
                .fill(12, 1, 12, 12, 2, 12, CellType.PASSABLE)
                .door(12, 1, 12, 12, 2, 12, OPEN_EW);
        Enclosure e = EnclosureCheck.run(h.grid(), 14, 1, 12, HANDS, BUDGET, 0L);
        assertEquals(Openness.CLOSEABLE, e.openness());
        assertEquals(List.of(new Pos(12, 1, 12)), e.doorsToShut(),
                "shutting the inner door closes this room; the front door is further off");
        assertEquals(21, e.space().size(), "the east half only, three by seven");
    }

    @Test
    void twoRoomsBehindAShutFrontDoorAreOneSpace() {
        House h = house(SHUT_NS, 10).roof();
        h.world.fill(12, 1, LO + 1, 12, 9, HI - 1, CellType.GROUND)
                .fill(12, 1, 12, 12, 2, 12, CellType.PASSABLE)
                .door(12, 1, 12, 12, 2, 12, OPEN_EW);
        Enclosure e = EnclosureCheck.run(h.grid(), 14, 1, 12, HANDS, BUDGET, 0L);
        assertEquals(Openness.OPENABLE, e.openness());
        assertEquals(21 + 21 + 2, e.space().size(), "both halves and both doorways");
        assertTrue(e.shelter());
    }

    @Test
    void aShutInnerDoorBetweenTheBodyAndAnOpenFrontDoorMakesTheRoomOpenable() {
        House h = house(OPEN_NS, 10).roof();
        h.world.fill(12, 1, LO + 1, 12, 9, HI - 1, CellType.GROUND)
                .fill(12, 1, 12, 12, 2, 12, CellType.PASSABLE)
                .door(12, 1, 12, 12, 2, 12, SHUT_EW);
        Enclosure e = EnclosureCheck.run(h.grid(), 14, 1, 12, HANDS, BUDGET, 0L);
        assertEquals(Openness.OPENABLE, e.openness(),
                "the way out passes a shut door, whatever stands open beyond it");
        assertEquals(22, e.space().size(), "the east half, and the inner doorway up to its panel");
    }

    // ── holes ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aOneHighHoleLetsSmallThingsIntoAClosedHouse() {
        Enclosure e = check(house(SHUT_NS).roof().gap(HI, 12, 1));
        assertEquals(Openness.OPENABLE, e.openness(), "a Person does not fit it");
        assertEquals(Holes.SMALL, e.holes(), "a baby zombie does");
    }

    @Test
    void aOneHighHoleStillLetsThemInOnceTheDoorIsShut() {
        Enclosure e = check(house(OPEN_NS).roof().gap(HI, 12, 1));
        assertEquals(Openness.CLOSEABLE, e.openness());
        assertEquals(Holes.SMALL, e.holes(), "shutting the door does not close the hole");
    }

    @Test
    void aOneHighHoleIntoASealedCupboardLetsNothingIn() {
        House h = house(SHUT_NS).roof();
        h.world.fill(HI + 2, 1, 11, HI + 2, 9, 13, CellType.GROUND)
                .fill(HI + 1, 1, 11, HI + 1, 9, 11, CellType.GROUND)
                .fill(HI + 1, 1, 13, HI + 1, 9, 13, CellType.GROUND)
                .fill(HI + 1, 2, 12, HI + 1, 9, 12, CellType.GROUND);
        Enclosure e = check(h.gap(HI, 12, 1));
        assertEquals(Holes.NONE, e.holes());
    }

    // ── the world ────────────────────────────────────────────────────────────────────────────

    private static Enclosure check(House h) {
        return EnclosureCheck.run(h.grid(), 12, 1, 12, HANDS, BUDGET, 0L);
    }

    private static Enclosure check(NavGrid grid) {
        return EnclosureCheck.run(grid, 12, 1, 12, HANDS, BUDGET, 0L);
    }

    /** A house with its front door at x = 12; {@code door} &lt; 0 walls the doorway up instead. */
    private static House house(int door) {
        return house(door, DOOR_X);
    }

    private static House house(int door, int doorX) {
        String[] rows = new String[SIZE];
        StringBuilder row = new StringBuilder();
        for (int z = 0; z < SIZE; z++) {
            row.setLength(0);
            for (int x = 0; x < SIZE; x++) {
                boolean wall = (x == LO || x == HI) && z >= LO && z <= HI
                        || (z == LO || z == HI) && x >= LO && x <= HI;
                row.append(wall && !(z == LO && x == doorX && door >= 0) ? '#' : '1');
            }
            rows[z] = row.toString();
        }
        AsciiWorld world = AsciiWorld.of(rows);
        if (door >= 0) {
            world.fill(doorX, 3, LO, doorX, 9, LO, CellType.GROUND) // the lintel and the wall over it
                    .door(doorX, 1, LO, doorX, 2, LO, door);
        }
        return new House(world);
    }

    /** A drawn house and the edits the tests make to it. */
    private record House(AsciiWorld world) {
        /** A ceiling over the whole house at y = 4, three cells over the floor. */
        House roof() {
            this.world.fill(LO, 4, LO, HI, 4, HI, CellType.GROUND);
            return this;
        }

        /** A gap {@code tall} cells high in the wall at {@code (x, z)}, from the floor up. */
        House gap(int x, int z, int tall) {
            this.world.fill(x, 1, z, x, tall, z, CellType.PASSABLE)
                    .fill(x, tall + 1, z, x, 9, z, CellType.GROUND);
            return this;
        }

        NavGrid grid() {
            return window(this.world);
        }
    }

    private static NavGrid window(NavGrid inner) {
        return window(inner, 0, SIZE - 1);
    }

    /**
     * A grid that behaves like a capture: past the box, {@code cell} reads OBSTACLE and
     * {@code inBounds} says so — see {@code PathfinderSealedTest}.
     */
    private static NavGrid window(NavGrid inner, int min, int max) {
        return new NavGrid() {
            @Override
            public CellType cell(int x, int y, int z) {
                return inBounds(x, y, z) ? inner.cell(x, y, z) : CellType.OBSTACLE;
            }

            @Override
            public double surface(int x, int y, int z) {
                return inBounds(x, y, z) ? inner.surface(x, y, z) : 0.0;
            }

            @Override
            public int doorway(int x, int y, int z) {
                return inBounds(x, y, z) ? inner.doorway(x, y, z) : 0;
            }

            @Override
            public boolean inBounds(int x, int y, int z) {
                return x >= min && x <= max && z >= min && z <= max && y >= -10 && y <= 20;
            }
        };
    }
}
