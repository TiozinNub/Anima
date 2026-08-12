package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which rung of the escape ladder a body reaches for, and which it refuses. The fake world is
 * flat ground at y 63 with the body at y 64: a wall is two cells, burying it adds the ones
 * overhead.
 */
class EscapeStepTest {

    private final FakeContext ctx = new FakeContext();
    private final FakeProbe blocks = ctx.percepts.blocks;
    private final EscapeStep escape = new EscapeStep();

    private static final int FEET = FakeProbe.GROUND_Y + 1;

    @BeforeEach
    void standOnTheGround() {
        ctx.percepts.position = new Pos(0, FEET, 0);
        ctx.percepts.confinement = new Confinement(true, 4);
    }

    /** Fills both cells of the body's height at a column — one block of wall. */
    private void wall(int x, int z) {
        blocks.set(x, FEET, z, BlockKind.OTHER);
        blocks.set(x, FEET + 1, z, BlockKind.OTHER);
    }

    /** The name of the rung the executor would choose right now. */
    private String chosen() {
        Method best = null;
        double bestCost = Double.MAX_VALUE;
        for (Method method : escape.methods()) {
            if (method.applicable(ctx) && method.estimateCost(ctx) < bestCost) {
                best = method;
                bestCost = method.estimateCost(ctx);
            }
        }
        return best == null ? "none" : best.describe();
    }

    private List<Task> plan() {
        for (Method method : escape.methods()) {
            if (method.applicable(ctx) && method.describe().equals(chosen())) {
                return method.decompose(ctx);
            }
        }
        throw new IllegalStateException("nothing applicable");
    }

    // ── choosing by shape ────────────────────────────────────────────────────────────────────

    /**
     * Shut in a roofed room: standable ground just past the wall, so punch through. The roof is
     * what makes this the cheap rung — under open sky, cutting one block and climbing out wins.
     */
    @Test
    void aBodyShutInARoomCutsThroughTheWall() {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    wall(x, z);
                    blocks.set(x, FEET + 2, z, BlockKind.OTHER);
                }
            }
        }
        blocks.set(0, FEET + 2, 0, BlockKind.OTHER); // the roof over our own head
        assertEquals("cut through the wall", chosen());
        // Two cells of one block of wall, then a step out onto the open ground beyond it.
        List<Task> plan = plan();
        assertEquals(3, plan.size());
        assertTrue(plan.get(0) instanceof BreakBlock);
        assertTrue(plan.get(1) instanceof BreakBlock);
        assertTrue(plan.get(2) instanceof GoTo);
    }

    /**
     * Down a shaft the sideways rung must not win: nothing but rock past every wall, so cutting
     * into it only digs a pointless horizontal tunnel.
     */
    @Test
    void aBodyDownAShaftCutsAStairInsteadOfTunnellingSideways() {
        // Solid rock around and above, for further than a wall could plausibly be thick.
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                for (int y = FEET; y <= FEET + 3; y++) {
                    blocks.set(x, y, z, BlockKind.OTHER);
                }
            }
        }
        assertEquals("cut a stair upward", chosen());
    }

    /** A body with a lid on cannot jump, so its own ceiling is part of the cut. */
    @Test
    void theStairOpensOurOwnCeilingFirst() {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = FEET; y <= FEET + 3; y++) {
                    if (x == 0 && z == 0 && y <= FEET + 1) {
                        continue; // leave the body's own two cells open
                    }
                    blocks.set(x, y, z, BlockKind.OTHER);
                }
            }
        }
        List<Task> plan = plan();
        assertTrue(plan.get(0) instanceof BreakBlock first
                && first.target().equals(new Pos(0, FEET + 2, 0)),
                "the lid over our own head goes first, or there is no jump to make");
    }

    // ── refusing ─────────────────────────────────────────────────────────────────────────────

    /** No arm to dig with, and it still says so — this rung must never be optional. */
    @Test
    void abodyWithNothingToDigWithStillSaysSo() {
        ctx.profile = TestSpecies.with(ProfileAspect.BODY_CAN_DIG, 0);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    wall(x, z);
                }
            }
        }
        assertEquals("call for help", chosen());
    }

    /**
     * There is a landing under every floor, so an earlier cut of the lowering rung applied in the
     * middle of a meadow. What matters is height above the ground AROUND the body.
     */
    @Test
    void itWillNotMineItsWayDownThroughFlatGround() {
        assertFalse(escape.methods().get(2).applicable(ctx));
    }

    /** Swinging at what you cannot see is how a body opens a wall into a lake. */
    @Test
    void itWillNotCutIntoWater() {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    blocks.set(x, FEET, z, BlockKind.WATER);
                    blocks.set(x, FEET + 1, z, BlockKind.WATER);
                }
            }
        }
        assertEquals("call for help", chosen());
    }

    @Test
    void itWillNotCutIntoAnUnloadedColumn() {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    wall(x, z);
                    blocks.markUnloaded(x, z);
                }
            }
        }
        assertEquals("call for help", chosen());
    }

    /**
     * The last rung fails on purpose: failing is what puts the drive on its cooldown instead of
     * shouting every tick, and what tells the board a body has admitted it cannot help itself.
     */
    @Test
    void sayingSoIsAFailure() {
        EscapeStep.Stuck stuck = new EscapeStep.Stuck();
        assertEquals(TaskStatus.FAILED, stuck.tick(ctx));
        assertEquals("shut in with no way out", stuck.failureDetail());
    }

    // ── lowering yourself ────────────────────────────────────────────────────────────────────

    /**
     * Too high to jump down safely, so take the floor out. The old "never break the block you are
     * standing on" rule forbade this and contradicted a shipped decision: un-building a mast is
     * mining it.
     */
    @Test
    void aBodyStrandedTooHighBreaksTheFloorToGetDown() {
        // A mast of its own making: five blocks up from the ground, body on top, nothing adjacent.
        for (int y = FakeProbe.GROUND_Y + 1; y <= FakeProbe.GROUND_Y + 5; y++) {
            blocks.set(0, y, 0, BlockKind.OTHER);
        }
        ctx.percepts.position = new Pos(0, FakeProbe.GROUND_Y + 6, 0);
        assertEquals("lower yourself", chosen());

        // The whole descent in one act: feet five above the landing, three of which it can fall,
        // so two blocks come out from under it, top down.
        List<Task> plan = plan();
        assertEquals(2, plan.size());
        for (int i = 0; i < 2; i++) {
            assertTrue(plan.get(i) instanceof BreakBlock cut
                    && cut.target().equals(new Pos(0, FakeProbe.GROUND_Y + 5 - i, 0)),
                    "cut " + i + " should be one lower than the last");
        }
    }

    /** One break per grant let wander steal the wheel mid-descent: a settler chewed a mound's whole rim away. */
    @Test
    void theDescentIsOneCommittedActNotOneBlock() {
        for (int y = FakeProbe.GROUND_Y + 1; y <= FakeProbe.GROUND_Y + 9; y++) {
            blocks.set(0, y, 0, BlockKind.OTHER);
        }
        ctx.percepts.position = new Pos(0, FakeProbe.GROUND_Y + 10, 0);
        assertEquals(6, plan().size(), "nine above the landing, three of which it can fall");
    }

    @Test
    void itStopsLoweringOnceTheFallIsSurvivable() {
        for (int y = FakeProbe.GROUND_Y + 1; y <= FakeProbe.GROUND_Y + 2; y++) {
            blocks.set(0, y, 0, BlockKind.OTHER);
        }
        ctx.percepts.position = new Pos(0, FakeProbe.GROUND_Y + 3, 0);
        assertFalse(escape.methods().get(2).applicable(ctx), "three down is a drop, not a dig");
    }

    /** Know where you land: a column it cannot read is not a landing, it is a guess. */
    @Test
    void itWillNotBreakTheFloorBesideSomethingItCannotSee() {
        for (int y = FakeProbe.GROUND_Y + 1; y <= FakeProbe.GROUND_Y + 5; y++) {
            blocks.set(0, y, 0, BlockKind.OTHER);
        }
        ctx.percepts.position = new Pos(0, FakeProbe.GROUND_Y + 6, 0);
        blocks.markUnloaded(1, 0);
        assertFalse(escape.methods().get(2).applicable(ctx));
    }
}
