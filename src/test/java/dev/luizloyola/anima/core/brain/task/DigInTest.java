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

    @org.junit.jupiter.api.AfterEach
    void nothingKeptOff() {
        DigIn.keepOffBy((x, z) -> false);
    }

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
        assertNull(DigIn.where(ctx), "nothing carried, and stone dug by hand gives nothing to close it with");
        ctx.percepts.inventory.set(0, ItemStack.of(DIRT, 4, 64));
        ctx.profile = TestSpecies.with(ProfileAspect.NIGHT_SHELTER_PRESSURE, 0.7);
        assertFalse(DigIn.possible(ctx), "off unless the species says so");
    }

    // ── the dig ──────────────────────────────────────────────────────────────────────────────

    @Test
    void itDigsThreeDownPutsTheLidBackAndRemembersThePocketFirst() {
        List<Task> plan = dig();
        assertInstanceOf(Centre.class, plan.get(0), "off-centre it stands on the rim and never drops in");
        assertEquals(List.of("break (0, 63, 0)", "break (0, 62, 0)", "break (0, 61, 0)"),
                plan.subList(1, 4).stream().map(t -> ((PrimitiveTask) t).describe()).toList());
        PlaceBlock lid = assertInstanceOf(PlaceBlock.class, plan.get(4));
        assertEquals(LID, lid.target());
        assertEquals(DIRT, lid.itemId());
        assertEquals(FEET, assertInstanceOf(SealIn.class, plan.get(5)).feet());
        PoiMemory pocket = DigIn.begun(ctx);
        assertNotNull(pocket, "written before the first block comes out");
        assertEquals(FEET, pocket.anchor());
        assertEquals(LID, ShelterNoter.cover(pocket));
        assertTrue(new DigIn().reshapesGround(), "the escape drive does not judge a body mid-dig");
    }

    @Test
    void anEmptyPackClosesThePocketWithTheDirtItDigs() {
        ctx.percepts.inventory.set(0, ItemStack.EMPTY);
        ctx.percepts.blocks.drops.put(new Pos(0, 62, 0), DIRT);
        assertEquals(new DigIn.Pocket(FEET, LID), DigIn.where(ctx), "a settler with nothing waited her first night out");
        List<Task> plan = dig();
        assertTrue(plan.stream().filter(BreakBlock.class::isInstance).map(BreakBlock.class::cast)
                .allMatch(BreakBlock::intoHand), "taken into the pack, so the lid is at hand");
        assertEquals(DIRT, plan.stream().filter(PlaceBlock.class::isInstance).map(PlaceBlock.class::cast)
                .findFirst().orElseThrow().itemId());
    }

    @Test
    void aColumnWhoseBlocksGiveNothingIsPassedForOneThatGivesALid() {
        ctx.percepts.inventory.set(0, ItemStack.EMPTY);
        ctx.percepts.blocks.drops.put(new Pos(0, 63, 0), "minecraft:stone_button"); // not a block to lay
        ctx.percepts.blocks.drops.put(new Pos(1, 63, 0), DIRT);
        DigIn.Pocket pocket = DigIn.where(ctx);
        assertNotNull(pocket);
        assertEquals(1, pocket.feet().x(), "under the feet gives nothing a walk lays: " + pocket);
    }

    @Test
    void aDigCutShortCarriesOnWhereItStopped() {
        dig();
        world.put(LID, CellType.PASSABLE);
        world.put(new Pos(0, 62, 0), CellType.PASSABLE);
        ctx.percepts.position = new Pos(0, 62, 0);
        List<Task> plan = dig();
        assertInstanceOf(Centre.class, plan.get(0));
        assertEquals("break (0, 61, 0)", ((PrimitiveTask) plan.get(1)).describe());
        assertInstanceOf(PlaceBlock.class, plan.get(2));
        assertEquals(1, ctx.knowledge.sighted(PoiKind.SHELTER).size(), "the same pocket");
    }

    @Test
    void aBodyOutOfItsPocketGoesBackToItBeforeDiggingAnother() {
        dig();
        world.put(new Pos(0, 62, 0), CellType.PASSABLE);
        world.put(FEET, CellType.PASSABLE);
        ctx.percepts.position = new Pos(3, 64, 1);
        assertEquals(new DigIn.Pocket(FEET, LID), DigIn.where(ctx), "a settler dug a second one beside it");
        List<Task> plan = dig();
        assertTrue(((PrimitiveTask) plan.get(0)).describe().startsWith("goto (0, 64, 0)"), "to its top");
        assertInstanceOf(Centre.class, plan.get(1));
        assertEquals("break (0, 63, 0)", ((PrimitiveTask) plan.get(2)).describe(), "the lid, to drop in");
        assertEquals(LID, assertInstanceOf(PlaceBlock.class, plan.get(3)).target(), "and back on");
        assertEquals(1, ctx.knowledge.sighted(PoiKind.SHELTER).size(), "one night, one pocket");
    }

    @Test
    void inThePocketWithTheLidOnItHolds() {
        dig();
        world.put(new Pos(0, 62, 0), CellType.PASSABLE);
        world.put(FEET, CellType.PASSABLE);
        ctx.percepts.position = FEET;
        List<Task> plan = dig();
        assertEquals(1, plan.size());
        assertInstanceOf(Idle.class, plan.get(0), "no second seal, no second line in the journal");
    }

    @Test
    void theSealHoldsOnlyWithTheFeetInThePocketAndTheLidIn() {
        ctx.percepts.position = new Pos(0, 64, 0);
        assertEquals(TaskStatus.FAILED, new SealIn(0, 61, 0).tick(ctx), "standing on the lid");
        assertEquals(0, ctx.percepts.rechecks);
        ctx.percepts.position = FEET;
        world.put(LID, CellType.PASSABLE);
        assertEquals(TaskStatus.FAILED, new SealIn(0, 61, 0).tick(ctx), "no lid");
        world.remove(LID);
        assertEquals(TaskStatus.SUCCESS, new SealIn(0, 61, 0).tick(ctx));
        assertEquals(1, ctx.percepts.rechecks, "the space is read again with the lid on");
    }

    @Test
    void aPocketGivenUpIsLeftAloneAndAnotherFound() {
        dig();
        Method giveUp = new DigIn().methods().get(1);
        assertTrue(giveUp.applicable(ctx));
        for (Task task : giveUp.decompose(ctx)) {
            assertEquals(TaskStatus.SUCCESS, ((PrimitiveTask) task).tick(ctx));
        }
        assertNull(DigIn.begun(ctx), "forgotten, so escape is no longer held off");
        DigIn.Pocket next = DigIn.where(ctx);
        assertNotNull(next);
        assertTrue(!next.feet().equals(FEET), "another column: " + next);
    }

    @Test
    void theCentringStepWaitsForTheBodyToReachTheMiddle() {
        Centre centre = new Centre();
        assertEquals(TaskStatus.RUNNING, centre.tick(ctx));
        assertEquals(1, ctx.riser.centres);
        assertEquals(TaskStatus.RUNNING, centre.tick(ctx));
        ctx.riser.state = dev.luizloyola.anima.core.brain.act.RiseState.RISEN;
        assertEquals(TaskStatus.SUCCESS, centre.tick(ctx));
    }

    // ── ground somebody keeps ────────────────────────────────────────────────────────────────

    @Test
    void onABuildingsGroundItWalksOffItAndDigsThere() {
        DigIn.keepOffBy((x, z) -> Math.abs(x) <= 10 && Math.abs(z) <= 10);
        DigIn.Pocket pocket = DigIn.where(ctx);
        assertNotNull(pocket, "a settler on the hut's pad at dusk dug nothing and slept in the open");
        assertEquals(12, Math.max(Math.abs(pocket.feet().x()), Math.abs(pocket.feet().z())),
                "the nearest column clear of the kept ground and the one beside it: " + pocket);
        List<Task> plan = dig();
        assertTrue(((PrimitiveTask) plan.get(0)).describe().startsWith("goto"), "walks there first");
        assertTrue(ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .anyMatch(e -> e.detail().contains("off the ground kept round a building")));
    }

    @Test
    void aBuildingThatGivesCoverIsStayedIn() {
        DigIn.keepOffBy((x, z) -> Math.abs(x) <= 10 && Math.abs(z) <= 10);
        ctx.percepts.enclosure = new Enclosure(Enclosure.Openness.OPENABLE, Enclosure.Holes.NONE, true,
                ctx.percepts.position, java.util.Set.of(ctx.percepts.position), List.of(), List.of(), 1L);
        assertTrue(method("stay in").applicable(ctx), "a half-built house with its roof on and its door shut");
        assertEquals("stay in", firstApplicable(), "stayed in, before any dig is looked for");
    }

    @Test
    void waitingTheNightOutIsSaidOnceAndWhy() {
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                world.put(new Pos(x, 60, z), CellType.WATER);
            }
        }
        assertEquals("wait the night out", firstApplicable());
        Method wait = method("wait the night out");
        wait.decompose(ctx);
        wait.decompose(ctx);
        List<String> said = ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .map(e -> e.detail()).filter(d -> d.startsWith("waiting the night out")).toList();
        assertEquals(List.of("waiting the night out at (0, 64, 0): no shelter in reach, and no safe ground to "
                + "dig in nearby"), said, "one line a night, never a silent night");
    }

    private String firstApplicable() {
        return new NightShelterStep().methods().stream().filter(m -> m.applicable(ctx)).findFirst()
                .orElseThrow().describe();
    }

    @Test
    void itNeverDigsBesideGroundKeptOff() {
        DigIn.keepOffBy((x, z) -> x <= -2);
        world.put(new Pos(1, 62, 0), CellType.WATER);
        DigIn.Pocket pocket = DigIn.where(ctx);
        assertNotNull(pocket);
        assertTrue(pocket.feet().x() >= 0, "a column beside kept ground is refused: " + pocket);
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

    // ── no hole left behind ──────────────────────────────────────────────────────────────────

    @Test
    void bySunriseItRisesOutLayingTheDugDirtBack() {
        inThePocketAtSunrise();
        for (int y = 61; y <= 63; y++) {
            ctx.percepts.blocks.drops.put(new Pos(1, y, 0), DIRT);
        }
        assertEquals(EscapeInstinct.pressure(ctx.profile), new EscapeInstinct().pressure(ctx),
                "out, sealed or not: the last rise starts a step down, where it could walk off");
        assertEquals("fill the pocket in", firstEscape(), "before any stair is cut");
        List<Task> plan = fillPlan();
        assertEquals("try: break (0, 63, 0)", ((CompoundTask) plan.get(0)).describe(), "the lid first, into the pack");
        assertEquals(List.of(DIRT, DIRT, DIRT), risesWith(plan), "one rise a cell, its block laid where it stood");
        assertInstanceOf(GiveUpShelter.class, plan.get(plan.size() - 1));
        assertTrue(said("filling the pocket in at (0, 64, 0)"));
    }

    @Test
    void eachCellGetsWhatTheGroundBesideItGivesWhenThatIsAtHand() {
        String cobble = "minecraft:cobblestone";
        BlocksToCross.layableBy(id -> id.equals(DIRT) || id.equals(cobble));
        inThePocketAtSunrise();
        ctx.percepts.inventory.set(0, ItemStack.of(cobble, 5, 64));
        ctx.percepts.inventory.set(1, ItemStack.of(DIRT, 1, 64));
        ctx.percepts.blocks.drops.put(new Pos(1, 63, 0), DIRT); // stone below, dug by hand: nothing
        assertEquals(List.of(cobble, cobble, DIRT), risesWith(fillPlan()),
                "the dirt by the dirt layer, the most at hand below it");
    }

    @Test
    void shortOfBlocksItFillsWhatItCanAndWithNoneItCutsAStair() {
        inThePocketAtSunrise();
        ctx.percepts.inventory.set(0, ItemStack.EMPTY);
        ctx.percepts.blocks.drops.put(LID, DIRT);
        assertEquals(List.of(DIRT), risesWith(fillPlan()), "the lid's own dirt");
        assertTrue(said("1 of 3 cells: short of blocks"));
        ctx.percepts.blocks.drops.clear();
        assertNull(DigIn.toFill(ctx), "nothing to fill with");
        assertEquals(0.0, new EscapeInstinct().pressure(ctx), "only shut in sends it out now");
        ctx.percepts.confinement = new Confinement(true, 2);
        assertEquals(EscapeInstinct.pressure(ctx.profile), new EscapeInstinct().pressure(ctx));
        assertFalse(new EscapeStep().methods().stream().filter(m -> m.describe().equals("fill the pocket in"))
                .findFirst().orElseThrow().applicable(ctx), "the escape cuts its way out, as before");
    }

    @Test
    void onlyItsOwnPocketByDayFromInsideIsFilled() {
        inThePocketAtSunrise();
        assertNotNull(DigIn.toFill(ctx));
        ctx.percepts.timeOfDay = 18_000L;
        assertNull(DigIn.toFill(ctx), "at night it stays in");
        ctx.percepts.timeOfDay = 0L;
        ctx.percepts.position = new Pos(0, 64, 0);
        assertNull(DigIn.toFill(ctx), "on top of it: out already");
        ctx.percepts.position = new Pos(3, 64, 0);
        assertNull(DigIn.toFill(ctx), "left it by a flight or a fall: not come back for");
        ctx.percepts.position = FEET;
        for (Task task : fillPlan()) {
            if (task instanceof GiveUpShelter forget) {
                assertEquals(TaskStatus.SUCCESS, forget.tick(ctx));
            }
        }
        assertNull(DigIn.toFill(ctx), "filled once: forgotten");
    }

    private void inThePocketAtSunrise() {
        dig();
        world.put(new Pos(0, 62, 0), CellType.PASSABLE);
        world.put(FEET, CellType.PASSABLE);
        ctx.percepts.position = FEET;
        ctx.percepts.timeOfDay = 0L;
        ctx.percepts.time = 6_000L;
    }

    private List<Task> fillPlan() {
        PoiMemory pocket = DigIn.toFill(ctx);
        assertNotNull(pocket);
        return DigIn.fillSteps(ctx, pocket);
    }

    private static List<String> risesWith(List<Task> plan) {
        return plan.stream().filter(Try.class::isInstance).map(t -> ((Try) t).attempt())
                .filter(Rise.class::isInstance).map(t -> ((Rise) t).spec().name()).map(n -> "minecraft:" + n)
                .toList();
    }

    private boolean said(String text) {
        return ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .anyMatch(e -> e.detail().contains(text));
    }

    private String firstEscape() {
        return new EscapeStep().methods().stream().filter(m -> m.applicable(ctx)).findFirst()
                .orElseThrow().describe();
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
    void aPocketLastsTheNightItWasDugWhateverIsAnsweredBesideIt() {
        ctx.knowledge.note(ShelterNoter.dugIn(FEET, LID, 0L), 16);
        ShelterNoter.note(Enclosure.open(new Pos(1, 63, 0), 5L), ctx.knowledge, 16);
        assertEquals(1, ctx.knowledge.sighted(PoiKind.SHELTER).size(),
                "a body stepped out of it beside it forgot it, and dug a second (2026-10-20)");
        ctx.percepts.position = FEET;
        assertNotNull(DigIn.begun(ctx));
        ctx.percepts.time = DigIn.NIGHT_TICKS;
        assertNull(DigIn.begun(ctx), "tomorrow night's is another");
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
