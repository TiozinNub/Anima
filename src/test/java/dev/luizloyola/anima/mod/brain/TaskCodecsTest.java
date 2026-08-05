package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.WanderStep;
import dev.luizloyola.anima.core.nav.Gait;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A plan writes itself down and comes back as it was — including how far it had got, which is the
 * half that decides whether an agent notices.
 *
 * <p>Through {@code JsonOps}: the codecs are ops-agnostic and the test does not need a Minecraft.
 */
class TaskCodecsTest {

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

    @Test
    void everyRegisteredTypeIsReachableFromAnInstance() {
        // The dispatch is by concrete class; a type registered under a key its instances do not
        // map back to would encode fine and fail to parse, which is the worst way round.
        assertNotNull(TaskCodecs.keyOf(new Idle(1)));
        assertNotNull(TaskCodecs.keyOf(new GoTo(0, 0, 0)));
        assertNotNull(TaskCodecs.keyOf(new WanderStep(1)));
        assertTrue(TaskCodecs.keys().contains("anima:idle"));
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
}
