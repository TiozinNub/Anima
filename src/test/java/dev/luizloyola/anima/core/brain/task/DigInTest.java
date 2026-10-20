package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.instinct.EscapeInstinct;
import dev.luizloyola.anima.core.brain.instinct.NightShelterInstinct;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.core.store.Store;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Digging in for a night with no shelter in reach (Luiz, 2026-10-20).
 *
 * <p>A plain: solid natural ground under y = 64, the body standing at (0, 64, 0) with a dirt block
 * in its pack. Its pocket is (0, 61..62, 0), the lid back in at (0, 63, 0).
 */
class DigInTest {

    private static final String DIRT = "minecraft:dirt";
    private static final Pos FEET = new Pos(0, 61, 0);
    private static final Pos LID = new Pos(0, 63, 0);

    private final FakeContext ctx = new FakeContext();
    /** Cells that are not the plain's: water, lava, a cave, a dug cell. */
    private final Map<Pos, CellType> world = new HashMap<>();

    @BeforeEach
    void aBodyOnAPlainAtNight() {
        BlocksToCross.layableBy(DIRT::equals);
        ctx.profile = TestSpecies.with(ProfileAspect.NIGHT_SHELTER_PRESSURE, 0.7,
                ProfileAspect.NIGHT_DIG_IN, 1.0);
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.timeOfDay = 18_000L;
        ctx.percepts.inventory.set(0, ItemStack.of(DIRT, 4, 64));
        ctx.percepts.terrain = new NavGrid() {
            @Override
            public CellType cell(int x, int y, int z) {
                CellType drawn = world.get(new Pos(x, y, z));
                return drawn != null ? drawn : y < 64 ? CellType.GROUND : CellType.PASSABLE;
            }
        };
    }

    // ── where ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aPlainGivesAPocketStraightUnderTheFeet() {
        assertEquals(new DigIn.Pocket(FEET, LID), DigIn.where(ctx));
        assertEquals(0.7, new NightShelterInstinct().pressure(ctx), "nowhere else, so it digs");
    }

    @Test
    void groundThatWouldLetSomethingInIsRefused() {
        assertTrue(safeUnderFeet(), "the plain itself, before each change");
        world.put(new Pos(1, 62, 0), CellType.WATER);
        assertFalse(safeUnderFeet(), "water beside the pocket");
        world.clear();
        world.put(new Pos(-1, 59, 1), CellType.DANGER);
        assertFalse(safeUnderFeet(), "lava by the floor");
        world.clear();
        world.put(new Pos(0, 59, 0), CellType.PASSABLE);
        assertFalse(safeUnderFeet(), "a cave under the floor");
        world.clear();
        assertTrue(safeUnderFeet());
    }

    @Test
    void groundThatWouldFallOrIsSomebodysIsRefused() {
        ctx.percepts.blocks.loose.add(new Pos(0, 62, 0));
        assertFalse(safeUnderFeet(), "sand in the column");
        ctx.percepts.blocks.loose.clear();
        ctx.percepts.blocks.loose.add(new Pos(1, 61, 0));
        assertFalse(safeUnderFeet(), "gravel in a wall");
        ctx.percepts.blocks.loose.clear();
        ctx.percepts.blocks.set(0, 62, 0, Store.BLOCK);
        assertFalse(safeUnderFeet(), "a chest buried in the column");
    }

    @Test
    void waterBesideTheFeetMovesThePocketAway() {
        world.put(new Pos(1, 62, 0), CellType.WATER);
        DigIn.Pocket pocket = DigIn.where(ctx);
        assertNotNull(pocket);
        assertTrue(Math.abs(pocket.feet().x() - 1) >= 2 || Math.abs(pocket.feet().z()) >= 2,
                "dug clear of the water: " + pocket);
    }

    @Test
    void nowhereSafeAndNothingToCloseItWithDigNothing() {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                world.put(new Pos(x, 60, z), CellType.WATER);
            }
        }
        assertNull(DigIn.where(ctx));
        assertFalse(method("dig in").applicable(ctx));
        world.clear();
        ctx.percepts.inventory.set(0, ItemStack.EMPTY);
        assertFalse(DigIn.possible(ctx), "no block to put over its head");
        ctx.percepts.inventory.set(0, ItemStack.of(DIRT, 4, 64));
        ctx.profile = TestSpecies.with(ProfileAspect.NIGHT_SHELTER_PRESSURE, 0.7);
        assertFalse(DigIn.possible(ctx), "off unless the species says so");
    }

    // ── the dig ──────────────────────────────────────────────────────────────────────────────

    @Test
    void itDigsThreeDownPutsTheLidBackAndRemembersThePocketFirst() {
        List<Task> plan = dig();
        assertEquals(List.of("break (0, 63, 0)", "break (0, 62, 0)", "break (0, 61, 0)"),
                plan.subList(0, 3).stream().map(t -> ((PrimitiveTask) t).describe()).toList());
        PlaceBlock lid = assertInstanceOf(PlaceBlock.class, plan.get(3));
        assertEquals(LID, lid.target());
        assertEquals(DIRT, lid.itemId());
        assertEquals(FEET, assertInstanceOf(SealIn.class, plan.get(4)).feet());
        PoiMemory pocket = DigIn.begun(ctx);
        assertNotNull(pocket, "written before the first block comes out");
        assertEquals(FEET, pocket.anchor());
        assertEquals(LID, ShelterNoter.cover(pocket));
        assertTrue(new DigIn().reshapesGround(), "the escape drive does not judge a body mid-dig");
    }

    @Test
    void aDigCutShortCarriesOnWhereItStopped() {
        dig();
        world.put(LID, CellType.PASSABLE);
        world.put(new Pos(0, 62, 0), CellType.PASSABLE);
        ctx.percepts.position = new Pos(0, 62, 0);
        List<Task> plan = dig();
        assertEquals("break (0, 61, 0)", ((PrimitiveTask) plan.get(0)).describe());
        assertInstanceOf(PlaceBlock.class, plan.get(1));
        assertEquals(1, ctx.knowledge.sighted(PoiKind.SHELTER).size(), "the same pocket");
    }

    @Test
    void theLidOnTheSealIsAskedForAndTheSpaceReadAgain() {
        new SealIn(0, 61, 0).tick(ctx);
        assertEquals(1, ctx.percepts.rechecks);
    }

    // ── a pocket shut on purpose ─────────────────────────────────────────────────────────────

    @Test
    void escapeLeavesItsOwnPocketAloneUntilSunrise() {
        dig();
        ctx.percepts.position = FEET;
        ctx.percepts.confinement = new Confinement(true, 2);
        assertEquals(0.0, new EscapeInstinct().pressure(ctx), "dug in on purpose");
        ctx.percepts.timeOfDay = 0L;
        assertEquals(EscapeInstinct.pressure(ctx.profile), new EscapeInstinct().pressure(ctx),
                "sunrise: climb out");
    }

    @Test
    void aPocketSomebodyElseSealedIsStillEscaped() {
        ctx.percepts.position = FEET;
        ctx.percepts.confinement = new Confinement(true, 2);
        assertEquals(EscapeInstinct.pressure(ctx.profile), new EscapeInstinct().pressure(ctx));
    }

    @Test
    void aPocketIsNeitherRunForNorWalkedInto() {
        ctx.knowledge.note(ShelterNoter.dugIn(new Pos(5, 61, 0), new Pos(5, 63, 0), 0L), 16);
        assertNull(NightShelterStep.shelterToWalkTo(ctx));
        assertFalse(ShelterNoter.knownDoor(ctx.knowledge, new Pos(5, 63, 0)));
    }

    @Test
    void thePocketIsForgottenOnceTheBodyIsSeenOutOfIt() {
        ctx.knowledge.note(ShelterNoter.dugIn(FEET, LID, 0L), 16);
        ShelterNoter.note(Enclosure.open(new Pos(0, 61, 0), 5L), ctx.knowledge, 16);
        assertEquals(1, ctx.knowledge.sighted(PoiKind.SHELTER).size(),
                "asked from inside it, the lid off: still digging out");
        ShelterNoter.note(Enclosure.open(new Pos(9, 64, 9), 5L), ctx.knowledge, 16);
        assertEquals(1, ctx.knowledge.sighted(PoiKind.SHELTER).size(), "somewhere else entirely");
        ShelterNoter.note(Enclosure.open(new Pos(1, 63, 0), 5L), ctx.knowledge, 16);
        assertTrue(ctx.knowledge.sighted(PoiKind.SHELTER).isEmpty(), "climbed out beside it");
    }

    private boolean safeUnderFeet() {
        return DigIn.safe(ctx.percepts.terrain, ctx.percepts.blocks, 0, 64, 0);
    }

    private List<Task> dig() {
        Method dig = new DigIn().methods().get(0);
        assertTrue(dig.applicable(ctx));
        return dig.decompose(ctx);
    }

    private static Method method(String name) {
        return new NightShelterStep().methods().stream().filter(m -> m.describe().equals(name))
                .findFirst().orElseThrow();
    }
}
