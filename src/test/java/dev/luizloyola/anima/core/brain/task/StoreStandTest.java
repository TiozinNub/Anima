package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
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
}
