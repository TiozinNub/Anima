package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Furnace;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Where a body stands to reach a store: forest, 2026-10-02, a house chest found full sent the stow
 * to build a second one on the roof, by a stand no walk from inside could reach.
 */
class StoreStandTest {

    /** The house chest, against the house's east wall. */
    private static final Pos CHEST = new Pos(10, 64, 10);
    private static final int ROOF_Y = 67;

    /** A shut house: inside x 8..10, z 9..11, three high, walled and roofed; floor the ground. */
    private static void house(FakeContext ctx) {
        for (int x = 7; x <= 11; x++) {
            for (int z = 8; z <= 12; z++) {
                boolean wall = x == 7 || x == 11 || z == 8 || z == 12;
                for (int y = 64; y < ROOF_Y; y++) {
                    if (wall) {
                        ctx.percepts.blocks.set(x, y, z, BlockKind.OTHER);
                    }
                }
                ctx.percepts.blocks.set(x, ROOF_Y, z, BlockKind.OTHER);
            }
        }
        ctx.percepts.blocks.set(CHEST.x(), CHEST.y(), CHEST.z(), Store.BLOCK);
        ctx.claim(Store.POI, CHEST);
    }

    private static void home(FakeContext ctx, Pos hint) {
        ctx.depot = Optional.of(new Depot.Site(hint,
                Set.of(ChunkKey.at(ChunkKey.OVERWORLD, hint.x(), hint.z()))));
    }

    private static boolean inside(Pos cell) {
        return cell.x() >= 8 && cell.x() <= 10 && cell.z() >= 9 && cell.z() <= 11 && cell.y() == 64;
    }

    private static Pos walkedTo(List<Task> steps) {
        return steps.stream().filter(step -> step instanceof GoTo).map(step -> (GoTo) step)
                .map(walk -> new Pos(walk.x(), walk.y(), walk.z())).findFirst()
                .orElseThrow(() -> new AssertionError("no walk: " + steps));
    }

    @Test
    void aSecondStoreBesideAFullHouseChestGoesInsideNotOnTheRoof() {
        FakeContext ctx = new FakeContext();
        house(ctx);
        ctx.percepts.position = new Pos(9, 64, 11);
        ctx.knowledge.avoid(Store.POI, CHEST, 1_000_000L); // found full
        home(ctx, CHEST);
        Method openAStore = new EnsureStore().methods().get(1);

        assertTrue(openAStore.applicable(ctx));
        List<Task> steps = openAStore.decompose(ctx);
        Pos placed = steps.stream().filter(step -> step instanceof FoundPlace)
                .map(step -> ((FoundPlace) step).anchor()).findFirst().orElseThrow();

        assertTrue(inside(placed), "the chest goes on the floor beside the full one, not on the "
                + "roof its wall's column climbs to: " + placed);
        for (Task step : steps) {
            if (step instanceof GoTo walk) {
                assertTrue(inside(new Pos(walk.x(), walk.y(), walk.z())),
                        "and the stand is inside too, never on the roof: " + walk.describe());
            }
        }
    }

    @Test
    void theStandForAHouseChestIsInside() {
        FakeContext ctx = new FakeContext();
        house(ctx);
        ctx.percepts.position = new Pos(8, 64, 9);
        home(ctx, CHEST);

        Pos stand = walkedTo(new EnsureStore().methods().get(0).decompose(ctx));

        assertTrue(inside(stand), "beside the chest, under the roof: " + stand);
    }

    @Test
    void aChestIsNotReachedThroughTheCornerOfAWall() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.blocks.set(CHEST.x(), CHEST.y(), CHEST.z(), Store.BLOCK);
        ctx.percepts.blocks.set(11, 64, 10, BlockKind.OTHER);
        ctx.percepts.blocks.set(10, 64, 11, BlockKind.OTHER);
        ctx.percepts.position = new Pos(12, 64, 12); // outside the corner

        Pos stand = EnsureTable.WalkToKnown.standBeside(CHEST, ctx).orElseThrow();

        assertFalse(stand.equals(new Pos(11, 64, 11)),
                "the diagonal past both walls is nearest, but the arm would reach through them");
    }

    /** A side open at the chest's level over a pit is no stand; a floor one down is stood on there. */
    @Test
    void aStandBesideAChestHasAFloorUnderIt() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.blocks.set(CHEST.x(), CHEST.y(), CHEST.z(), Store.BLOCK);
        for (int[] side : Ground.SIDES) {
            ctx.percepts.blocks.set(CHEST.x() + side[0], CHEST.y(), CHEST.z() + side[1], BlockKind.OTHER);
        }
        Pos west = new Pos(CHEST.x() - 1, CHEST.y(), CHEST.z());
        for (int y = CHEST.y(); y >= CHEST.y() - 8; y--) {
            ctx.percepts.blocks.set(west.x(), y, west.z(), BlockKind.AIR); // a pit, eight deep
        }
        ctx.percepts.position = new Pos(west.x(), CHEST.y() - 8, west.z());

        assertTrue(EnsureTable.WalkToKnown.standBeside(CHEST, ctx).isEmpty(),
                "the only open side has its floor eight down, out of reach of the chest");

        ctx.percepts.blocks.set(west.x(), CHEST.y() - 2, west.z(), BlockKind.OTHER);
        assertEquals(new Pos(west.x(), CHEST.y() - 1, west.z()),
                EnsureTable.WalkToKnown.standBeside(CHEST, ctx).orElseThrow(),
                "with a floor one down, the stand is on it, not in the air over it");
    }

    @Test
    void aStoreNeverGoesOnTopOfAFurnace() {
        FakeContext ctx = new FakeContext();
        Pos furnace = new Pos(10, 64, 10);
        ctx.percepts.blocks.set(furnace.x(), furnace.y(), furnace.z(), Furnace.BLOCK);
        ctx.percepts.position = new Pos(14, 64, 14);

        Pos site = Ground.near(ctx, new Pos(10, 65, 10), 1, cell -> true).orElseThrow();

        assertNotEquals(new Pos(10, 65, 10), site, "a station's top is no floor for another");
        assertTrue(ctx.percepts.blocks.at(site.x(), site.y() - 1, site.z()).ground());
    }

    /**
     * Columns y 60..79 from x -332 and z -493, read from run/normal's region file: Elmer's base in a
     * quarry, its first chest full. {@code #} solid, {@code .} air, {@code W} water, {@code C} the
     * chest, {@code F} the furnace, {@code T} the workbench. Read after the loop, so
     * {@link #quarry} fills {@code (-327, 73, -487)}: the choice made shows it was not on offer then,
     * and here it ties the furnace's top and wins.
     */
    private static final String[] QUARRY = {
            "WWW................. WWW................. WWW................. WWW................. WWW................. WWW................. WWW.....#........... #WW..#####......#... #############...###. ####################",
            "WWW................. WWW................. WWW................. WWW................. WWW................. WWW................. WWW..####.....###... ##########....#####. ##############...### ####################",
            "WWW................. WWW................. WWW................. WWW................. WWW................. ##W................. #####........####... ##########.....##### ###############...## ####################",
            "WWW................. WWW................. #WW....#............ ###....#............ ####...#............ #######............. ########....#######. #############...#### ################...# ####################",
            "WWW................. WWW................. ###....#............ ####................ #####............... ######....##F....... #################### ##############...### #################... ####################",
            "WWW................. ##W................. ###....#............ ####................ #####.....#T........ #######...##C....... #################### ###############...## ##################.. ####################",
            "WWW................. #WW................. ####........#....... #####..#....#....... ######.......###.... ########...##..##### #################### ################...# #################### ####################",
            "WWW................. WWW................. ####....##.......... #########....#...... #######....########. #########..######### #################### #################..# #################### ####################",
            "WWW................. WWW................. ####.######...#..... #########.....#..... ########....######## #################### #################### #################### #################### ####################",
            "WWW................. WWW................. ###..#######...#.... ##########.....#.... #################### #################### #################### #################### #################### ####################",
    };

    private static void quarry(FakeContext ctx) {
        for (int row = 0; row < QUARRY.length; row++) {
            String[] columns = QUARRY[row].split(" ");
            for (int col = 0; col < columns.length; col++) {
                for (int i = 0; i < columns[col].length(); i++) {
                    BlockKind kind = switch (columns[col].charAt(i)) {
                        case '#' -> BlockKind.OTHER;
                        case 'W' -> BlockKind.WATER;
                        case 'C' -> Store.BLOCK;
                        case 'F' -> Furnace.BLOCK;
                        case 'T' -> Workbench.BLOCK;
                        default -> BlockKind.AIR;
                    };
                    ctx.percepts.blocks.set(-332 + col, 60 + i, -493 + row, kind);
                }
            }
        }
        ctx.percepts.blocks.set(-327, 73, -487, BlockKind.OTHER);
    }

    /**
     * run/normal, 2026-10-02: the chest went on the furnace at (-327, 73, -489), to be reached from
     * (-328, 73, -489), eight above the quarry floor. Elmer walked to the floor and "arrived" there
     * 2,129 times a minute.
     */
    @Test
    void aSecondStoreInTheQuarryHasFootingAndSoDoesItsStand() {
        FakeContext ctx = new FakeContext();
        quarry(ctx);
        Pos full = new Pos(-327, 72, -488);
        ctx.claim(Store.POI, full);
        ctx.knowledge.avoid(Store.POI, full, 1_000_000L);
        ctx.percepts.position = new Pos(-328, 65, -489);
        home(ctx, new Pos(-328, 71, -488));
        Method openAStore = new EnsureStore().methods().get(1);

        assertTrue(openAStore.applicable(ctx));
        List<Task> steps = openAStore.decompose(ctx);
        Pos placed = steps.stream().filter(step -> step instanceof FoundPlace)
                .map(step -> ((FoundPlace) step).anchor()).findFirst().orElseThrow();
        BlockProbe probe = ctx.percepts.blocks;
        BlockKind floor = probe.at(placed.x(), placed.y() - 1, placed.z());

        assertTrue(floor.ground(), "the chest stands on the ground, not on " + floor + " at "
                + placed);
        Pos stand = steps.stream().filter(step -> step instanceof GoTo).map(step -> (GoTo) step)
                .map(walk -> new Pos(walk.x(), walk.y(), walk.z())).findFirst()
                .orElse(ctx.percepts.position);
        assertNotEquals(BlockKind.AIR, probe.at(stand.x(), stand.y() - 1, stand.z()),
                "and its stand has a floor: " + stand);
    }
}
