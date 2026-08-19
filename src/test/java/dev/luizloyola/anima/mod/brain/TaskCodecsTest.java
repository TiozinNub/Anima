package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.arch.SourceTree;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Answer;
import dev.luizloyola.anima.core.brain.task.Face;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.SeekCompany;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.WanderStep;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A plan writes itself down and comes back as it was — including how far it had got, which is the
 * half that decides whether an agent notices.
 *
 * <p>Through {@code JsonOps}: the codecs are ops-agnostic and the test does not need a Minecraft.
 */
class TaskCodecsTest {

    /** Where Anima's own tasks live — the layer the scan below reads. */
    private static final String CORE = "dev.luizloyola.anima.core";

    @BeforeAll
    static void registerTheTypes() {
        AnimaTasks.install();
    }

    private static Task roundTrip(Task task) {
        var encoded = TaskCodecs.codec().encodeStart(JsonOps.INSTANCE, task).getOrThrow();
        return TaskCodecs.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }

    @Test
    void aWalkComesBackWithItsDestination() {
        GoTo before = new GoTo(12, -60, -34);
        GoTo after = assertInstanceOf(GoTo.class, roundTrip(before));
        assertEquals(12, after.x());
        assertEquals(-60, after.y());
        assertEquals(-34, after.z());
        assertEquals(before.gait(), after.gait());
    }

    @Test
    void aWalkAlreadyOrderedDoesNotOrderItselfAgain() {
        // `issued` is the difference between a walk under way and one about to start. A reload
        // that loses it re-issues the order, which is a body visibly hesitating.
        GoTo before = new GoTo(1, 2, 3, Gait.WALK).resume(true);
        assertTrue(assertInstanceOf(GoTo.class, roundTrip(before)).issued());
    }

    @Test
    void aPauseResumesWhereItWasRatherThanAtTheStart() {
        // 40 ticks into a 60-tick pause, a reload that forgets the counter pauses for 60 more.
        Idle before = new Idle(60).resume(20);
        Idle after = assertInstanceOf(Idle.class, roundTrip(before));
        assertEquals(60, after.ticks());
        assertEquals(20, after.remaining());
    }

    @Test
    void aCompoundCarriesWhatItWasBuiltWith() {
        WanderStep after = assertInstanceOf(WanderStep.class, roundTrip(new WanderStep(9)));
        assertEquals(9, after.radius());
    }

    /**
     * The guard the two social roots were missing. It asks the wider question on purpose: what an
     * instinct can grant is a subset of what Anima declares, and the executor writes its FRAMES
     * too, so a SUBTASK with no codec sinks a save exactly as a root would. Scanning the tree is
     * what makes it hold for the task rung 5 adds without anybody remembering this file exists.
     */
    @Test
    void everyTaskAnimaDeclaresCanWriteItselfDown() {
        java.util.List<Class<?>> declared = new java.util.ArrayList<>();
        for (SourceTree.JavaSource file
                : SourceTree.fromSystemProperty("anima.arch.sourceRoot").inPackage(CORE)) {
            collectTasks(loaded(file.path()), declared);
        }
        // A scan that finds nothing is a guard that passes by looking away — the same failure
        // SourceTree refuses for an empty tree.
        assertTrue(declared.size() >= 15,
                "only " + declared.size() + " task types found: the scan has lost the source tree");

        java.util.List<String> orphans = declared.stream()
                .filter(type -> !TaskCodecs.types().contains(type))
                .map(Class::getSimpleName)
                .toList();
        assertTrue(orphans.isEmpty(), () -> SourceTree.report(
                "every Task Anima declares must be registered with TaskCodecs — a plan holding an "
                        + "unregistered one cannot be saved, and the body loses it on reload",
                orphans));
    }

    /** The class a source file declares, or null for {@code package-info} and its kin. */
    private static Class<?> loaded(String path) {
        String binary = path.substring(0, path.length() - ".java".length()).replace('/', '.');
        try {
            // Not initialised: this only asks what a class IS, and running core's static
            // declarations from a scan would be a side effect nobody asked for.
            return Class.forName(binary, false, TaskCodecsTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** {@code type} and every class nested in it that a plan could hold — {@code EscapeStep.Stuck}. */
    private static void collectTasks(Class<?> type, java.util.List<Class<?>> found) {
        if (type == null) {
            return;
        }
        if (Task.class.isAssignableFrom(type) && !type.isInterface()
                && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            found.add(type);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectTasks(nested, found);
        }
    }

    @Test
    void aFoundPlaceComesBackWithItsKindAndAnchor() {
        // No other coverage exercises this codec (NotePlace's has none either) — the party-places
        // feature depends on this round trip: a claim founded mid-plan must survive a reload.
        dev.luizloyola.anima.core.brain.knowledge.PoiKind kind =
                dev.luizloyola.anima.core.brain.knowledge.PoiKind.register(
                        "test_found_place_codec", 1, "");
        dev.luizloyola.anima.core.brain.task.FoundPlace before =
                new dev.luizloyola.anima.core.brain.task.FoundPlace(kind, 88, 64, -12);
        var after = assertInstanceOf(dev.luizloyola.anima.core.brain.task.FoundPlace.class,
                roundTrip(before));
        assertEquals(kind, after.kind());
        assertEquals(new dev.luizloyola.anima.core.brain.sense.Pos(88, 64, -12), after.anchor());
    }

    @Test
    void everyRegisteredTypeIsReachableFromAnInstance() {
        // The dispatch is by concrete class; a type registered under a key its instances do not
        // map back to would encode fine and fail to parse, which is the worst way round.
        assertNotNull(TaskCodecs.keyOf(new Idle(1)));
        assertNotNull(TaskCodecs.keyOf(new GoTo(0, 0, 0)));
        assertNotNull(TaskCodecs.keyOf(new WanderStep(1)));
        assertNotNull(TaskCodecs.keyOf(new dev.luizloyola.anima.core.brain.task.FoundPlace(
                // same key+shape as the round trip test above — registration is idempotent, and
                // JUnit does not promise these two methods run in declaration order
                dev.luizloyola.anima.core.brain.knowledge.PoiKind.register(
                        "test_found_place_codec", 1, ""), 0, 0, 0)));
        assertTrue(TaskCodecs.keys().contains("anima:idle"));
        assertTrue(TaskCodecs.keys().contains("anima:found_place"));
    }

    @Test
    void aTaskFromAMissingModIsRefusedRatherThanSkipped() {
        // A saved plan naming a task type this build no longer has must error, not vanish — a plan
        // missing a limb still looks like a plan.
        //
        // (No test for an unregistered LIVE task: Task is sealed and every permitted
        // implementation is registered, so the compiler will not let this test fake one.)
        var unknown = com.google.gson.JsonParser.parseString("{\"task\":\"gone:chop\"}");
        var parsed = TaskCodecs.codec().parse(JsonOps.INSTANCE, unknown);
        assertTrue(parsed.isError());
        assertTrue(parsed.error().orElseThrow().message().contains("gone:chop"),
                "the complaint has to name the type, or nobody can act on it");
    }

    @Test
    void anObtainCarriesItsPursuedSetAndALiteralSpecSurvivesByContent() {
        // The spec is a literal (no mod ever declared "any plank") and the occurs-check's ancestor
        // set rides along — lose either and a reloaded settler forgets what not to craft.
        dev.luizloyola.anima.core.brain.task.ObtainItem before =
                new dev.luizloyola.anima.core.brain.task.ObtainItem(
                        dev.luizloyola.anima.core.inv.ItemSpec.anyOf(
                                java.util.Set.of("minecraft:oak_planks", "minecraft:birch_planks")),
                        6, java.util.Set.of("minecraft:wooden_axe"));
        var after = assertInstanceOf(dev.luizloyola.anima.core.brain.task.ObtainItem.class,
                roundTrip(before));
        assertEquals(6, after.count());
        assertEquals(before.spec().name(), after.spec().name(), "content-derived name, rebuilt");
        assertTrue(after.spec().matches("minecraft:birch_planks"));
        assertEquals(java.util.Set.of("minecraft:wooden_axe"), after.pursued());
    }

    @Test
    void theKitWrapAndTheShrugRoundTripWithTheirInnards() {
        // Both wrappers carry TASKS through the dispatch codec — the recursion the whole tree
        // format is built on, exercised one level deep from each.
        dev.luizloyola.anima.core.inv.ItemSpec axes = dev.luizloyola.anima.core.inv.ItemSpec.anyOf(
                java.util.Set.of("minecraft:wooden_axe", "minecraft:stone_axe"));
        dev.luizloyola.anima.core.brain.task.KittedErrand before =
                new dev.luizloyola.anima.core.brain.task.KittedErrand(
                        java.util.List.of(dev.luizloyola.anima.core.inv.ItemCall.want(axes, 1)),
                        new GoTo(4, -60, 9));
        var after = assertInstanceOf(dev.luizloyola.anima.core.brain.task.KittedErrand.class,
                roundTrip(before));
        assertEquals(1, after.calls().size());
        assertEquals(axes.name(), after.calls().get(0).spec().name());
        assertEquals(dev.luizloyola.anima.core.inv.ItemCall.Strength.WANT,
                after.calls().get(0).strength());
        assertInstanceOf(GoTo.class, after.work());

        var shrug = assertInstanceOf(dev.luizloyola.anima.core.brain.task.Try.class, roundTrip(
                new dev.luizloyola.anima.core.brain.task.Try(new GoTo(1, 2, 3))));
        assertInstanceOf(GoTo.class, shrug.attempt());
    }

    @Test
    void aCraftMidPauseComesBackMidPauseWithItsWholeRecipe() {
        // The recipe rides inline (a /reload can remove it from the book mid-craft), and the
        // pause counter is the part a body would feel restarting.
        dev.luizloyola.anima.core.craft.CraftRecipe recipe =
                new dev.luizloyola.anima.core.craft.CraftRecipe("minecraft:oak_planks",
                        dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:oak_planks", 4, 64),
                        java.util.List.of(new dev.luizloyola.anima.core.craft.CraftRecipe.Ingredient(
                                java.util.Set.of("minecraft:oak_log"), 1)),
                        false);
        dev.luizloyola.anima.core.brain.task.CraftStep before =
                new dev.luizloyola.anima.core.brain.task.CraftStep(recipe, 3).resume(1, 4);
        var after = assertInstanceOf(dev.luizloyola.anima.core.brain.task.CraftStep.class,
                roundTrip(before));
        assertEquals(3, after.times());
        assertEquals(1, after.done());
        assertEquals(4, after.workTicks());
        assertEquals(recipe, after.recipe(), "bill, output and table flag, byte for byte");
    }

    @Test
    void aTakeMidStackComesBackWithItsPhaseAndTally() {
        // The pause and moved tally are the whole point: without them a reload restarts the open
        // (visible time lost) or re-moves a stack already sitting in the pack (items invented).
        dev.luizloyola.anima.core.inv.ItemSpec logs = dev.luizloyola.anima.core.inv.ItemSpec.anyOf(
                java.util.Set.of("minecraft:oak_log"));
        dev.luizloyola.anima.core.brain.task.TakeItems before =
                new dev.luizloyola.anima.core.brain.task.TakeItems(new Pos(4, 64, 4), logs, 16)
                        .resume("MOVE", 3, 5);
        var after = assertInstanceOf(dev.luizloyola.anima.core.brain.task.TakeItems.class,
                roundTrip(before));
        assertEquals(new Pos(4, 64, 4), after.at());
        assertEquals(logs.name(), after.spec().name());
        assertEquals(16, after.count());
        assertEquals("MOVE", after.phaseName());
        assertEquals(3, after.pauseTicks());
        assertEquals(5, after.moved());
    }

    @Test
    void aPutMidStackComesBackWithItsPhaseAndTally() {
        dev.luizloyola.anima.core.inv.ItemSpec logs = dev.luizloyola.anima.core.inv.ItemSpec.anyOf(
                java.util.Set.of("minecraft:oak_log"));
        dev.luizloyola.anima.core.brain.task.PutItems before =
                new dev.luizloyola.anima.core.brain.task.PutItems(new Pos(4, 64, 4), logs, 8)
                        .resume("SETTLE", 2, 0);
        var after = assertInstanceOf(dev.luizloyola.anima.core.brain.task.PutItems.class,
                roundTrip(before));
        assertEquals(new Pos(4, 64, 4), after.at());
        assertEquals(logs.name(), after.spec().name());
        assertEquals(8, after.count());
        assertEquals("SETTLE", after.phaseName());
        assertEquals(2, after.pauseTicks());
        assertEquals(0, after.moved());
    }

    @Test
    void takeAndPutAreBothReachableByKey() {
        // The trap that broke a world save when FoundPlace shipped without one: an unregistered
        // task's key is null, and the dispatch codec writes that null straight into the save.
        dev.luizloyola.anima.core.inv.ItemSpec logs = dev.luizloyola.anima.core.inv.ItemSpec.anyOf(
                java.util.Set.of("minecraft:oak_log"));
        assertNotNull(TaskCodecs.keyOf(
                new dev.luizloyola.anima.core.brain.task.TakeItems(new Pos(0, 0, 0), logs, 1)));
        assertNotNull(TaskCodecs.keyOf(
                new dev.luizloyola.anima.core.brain.task.PutItems(new Pos(0, 0, 0), logs, 1)));
        assertTrue(TaskCodecs.keys().contains("anima:take_items"));
        assertTrue(TaskCodecs.keys().contains("anima:put_items"));
    }

    @Test
    void anAnswerRemembersWhoCalledAndFromWhere() {
        // A hail carries one cell and one id, and both are the whole task: come back without them
        // and the body is walking to nowhere on behalf of nobody.
        BeingId caller = BeingId.of(UUID.randomUUID());
        Answer after = assertInstanceOf(Answer.class,
                roundTrip(new Answer(caller, new Pos(8, -60, -3))));
        assertEquals(caller, after.who());
        assertEquals(new Pos(8, -60, -3), after.where());
    }

    @Test
    void aBeatComesBackWithItsSubjectAndWhatIsLeftOfIt() {
        BeingId caller = BeingId.of(UUID.randomUUID());
        Face after = assertInstanceOf(Face.class,
                roundTrip(new Face(caller, new Pos(2, 64, 2), 30).resume(11)));
        assertEquals(caller, after.who());
        assertEquals(new Pos(2, 64, 2), after.where());
        assertEquals(30, after.ticks());
        assertEquals(11, after.remaining(), "a beat that restarts is one both parties stand through");
    }

    @Test
    void aSeekAlreadyWalkingComesBackOnTheSameLeg() {
        // The nested walk is the mid-flight half: the mark for this target is already spent, so a
        // seek that came back before its walk would set off toward somebody else entirely.
        SeekCompany before = new SeekCompany().resume(new GoTo(20, 64, -4, Gait.WALK).resume(true));
        SeekCompany after = assertInstanceOf(SeekCompany.class, roundTrip(before));
        assertNotNull(after.walk());
        assertEquals(20, after.walk().x());
        assertEquals(-4, after.walk().z());
        assertTrue(after.walk().issued(), "a walk under way does not re-order itself");

        assertNull(assertInstanceOf(SeekCompany.class, roundTrip(new SeekCompany())).walk(),
                "and one still to choose a target comes back with nothing to resume");
    }
}
