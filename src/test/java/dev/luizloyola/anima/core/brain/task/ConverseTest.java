package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.SpeechEngine;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The turn-taking primitive atop {@link Speech}: obtain the encounter once, face the other body
 * every tick, speak the chooser's line on this body's turn, and give up on a snub once the
 * other's owed reply never comes.
 *
 * <p>Two facts pin how these are written, both surfaced reviewing Task 5's engine: {@code say}
 * and {@code system} carry no {@code closed()} guard of their own, and {@link Encounter#append}
 * throws once the record is finished — so the top-of-tick {@code encounter.closed() -> SUCCESS}
 * check must run before {@code face()} or any speech call, which
 * {@link #closedRecordEndsImmediately()} pins directly. And on a roster SHARED between two
 * {@link FakeSpeech}s, closing notifies only the closing engine's own listener, so
 * {@link #fullTwoPartyConversationEndsInSuccessForBoth()} asserts closure on the shared
 * {@link Encounter} itself rather than on both sides' {@code closedRecords}.
 */
class ConverseTest {

    private final FakeContext ctx = new FakeContext();
    /** A stranger nobody perceives — good enough whenever a test doesn't care about facing. */
    private final BeingId otherId = BeingId.of(AgentId.random());

    // ── rule 1: first tick joins or resumes; a closed record is SUCCESS ─────────────────────

    @Test
    @DisplayName("first tick joins a fresh encounter when none is open, crediting the hail as asked")
    void firstTickJoinsAFreshEncounter() {
        Converse converse = new Converse(otherId, Speech.Opening.I_HAILED);
        ctx.speech.chooser = (c, turn) -> null;

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));

        Encounter e = ctx.speech.current().orElseThrow();
        assertTrue(e.includes(ctx.self));
        assertTrue(e.includes(otherId.asPerson()));
        assertEquals(1, e.transcript().size(), "I_HAILED prefills exactly the hail");
        assertEquals(ctx.self, e.transcript().get(0).author());
        assertEquals(SpeechActs.HAIL.key(), e.transcript().get(0).act());
    }

    @Test
    @DisplayName("first tick resumes the already-open encounter rather than joining a duplicate")
    void firstTickResumesTheOpenEncounter() {
        Encounter existing = ctx.speech.join(otherId, Speech.Opening.THEY_HAILED);
        // A different opening than the pre-existing record's — proof current() wins, not join().
        Converse converse = new Converse(otherId, Speech.Opening.I_HAILED);
        ctx.speech.chooser = (c, turn) -> null;

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));

        assertSame(existing, ctx.speech.current().orElseThrow(), "the same record, not a second one");
        assertEquals(1, existing.transcript().size(), "still just the one THEY_HAILED hail — no re-prefill");
        assertEquals(otherId.asPerson(), existing.transcript().get(0).author());
    }

    @Test
    @DisplayName("an already-closed record ends the task before face() or any speech call runs")
    void closedRecordEndsImmediately() {
        Being counterpart = FakePercepts.personAt(new Pos(4, 64, 0), 4.0, "Rex");
        ctx.percepts.beings = List.of(counterpart);
        Converse converse = new Converse(counterpart.id(), Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "still open on the first tick");
        Encounter e = ctx.speech.current().orElseThrow();
        ctx.gazer.asked = false; // clear the first (legitimate) claim so this tick is unambiguous
        int saidBefore = ctx.speech.saidLines.size();
        ctx.speech.close(e); // as if the other side's engine ended it between our ticks

        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));
        assertFalse(ctx.gazer.asked, "the closed check comes first — face() must not run this tick");
        assertEquals(saidBefore, ctx.speech.saidLines.size(), "and no line may be appended after close");
    }

    // ── rule 2: face the other every tick; no claim when unperceived ────────────────────────

    @Test
    @DisplayName("faces the other's live position every tick, re-asked each time, at WORK priority")
    void facesTheOtherEveryTick() {
        Being counterpart = FakePercepts.personAt(new Pos(6, 64, 0), 6.0, "Rex");
        ctx.percepts.beings = List.of(counterpart);
        Converse converse = new Converse(counterpart.id(), Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;

        converse.tick(ctx);
        converse.tick(ctx);

        assertTrue(ctx.gazer.asked);
        assertEquals(6.5, ctx.gazer.x, 1e-9, "the centre of their cell");
        assertEquals(65.5, ctx.gazer.y, 1e-9, "their face height, not their boots");
        assertEquals(0.5, ctx.gazer.z, 1e-9);
        assertEquals(Gazer.Priority.WORK, ctx.gazer.priority,
                "standing in this conversation IS the act, same rank Face uses");
        assertEquals(2, ctx.gazer.claims, "re-asked every tick, not claimed once");
    }

    @Test
    @DisplayName("asks for no gaze claim when the other is not currently perceived")
    void noGazeWhenNotPerceived() {
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;
        // ctx.percepts.beings is empty by default — the other is out of sight

        converse.tick(ctx);

        assertFalse(ctx.gazer.asked, "remembered position is the sensor's business, not ours");
    }

    @Test
    @DisplayName("faces the held record's real counterpart, not who the task was built expecting")
    void facesTheHeldRecordsCounterpartEvenIfConstructedForSomebodyElse() {
        // current() can hand back an encounter this body was already in with somebody else — a
        // body swept into that record must look at who it is actually talking to.
        Being actual = FakePercepts.personAt(new Pos(5, 64, 0), 5.0, "Actual");
        ctx.percepts.beings = List.of(actual);
        ctx.speech.join(actual.id(), Speech.Opening.QUIET);
        Converse converse = new Converse(otherId, Speech.Opening.QUIET); // built for somebody else
        ctx.speech.chooser = (c, turn) -> null;

        converse.tick(ctx);

        assertTrue(ctx.gazer.asked);
        assertEquals(5.5, ctx.gazer.x, 1e-9, "the record's actual counterpart's cell");
    }

    // ── rule 3: speak on this body's turn; a line that ends the record is SUCCESS ───────────

    @Test
    @DisplayName("speaks the chooser's line on its turn, said and journaled")
    void speaksTheChosenLineAndJournalsIt() {
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.GREETING);

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "greeting doesn't end the chat");

        assertEquals(1, ctx.speech.saidLines.size());
        assertEquals(SpeechActs.GREETING.key(), ctx.speech.saidLines.get(0).act());
        assertEquals(ctx.self, ctx.speech.saidLines.get(0).author());
        assertTrue(journaled(ctx, "said " + SpeechActs.GREETING.key()));
    }

    @Test
    @DisplayName("a chosen line that ends the encounter returns SUCCESS")
    void aLineThatEndsTheEncounterSucceeds() {
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.END_CHAT);

        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));

        assertEquals(1, ctx.speech.closedRecords.size());
        assertTrue(ctx.speech.closedRecords.get(0).closed());
        assertTrue(journaled(ctx, "said " + SpeechActs.END_CHAT.key()));
    }

    @Test
    @DisplayName("a null choice is silence — stays RUNNING, nothing said")
    void silenceStaysRunning() {
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));

        assertTrue(ctx.speech.saidLines.isEmpty());
    }

    // ── rule 4: give up on an expired obligation — IGNORED, journaled, SUCCESS ──────────────

    @Test
    @DisplayName("gives up on an unanswered obligation once patience runs out, and the record closes")
    void givesUpOnAnUnansweredObligation() {
        ctx.speech.caps = new SpeechEngine.Caps(60, 6_000, 1_200, 10); // patience shrunk to 10 ticks
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = scripted(Chooser.Line.of(SpeechActs.REQUEST_END_CHAT),
                Chooser.Line.of(SpeechActs.DEFLECT));

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "proposes ending — the other now owes a reply");
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx),
                "a second self line in a row hits the monologue cap");
        assertFalse(ctx.speech.maySpeak(ctx.speech.current().orElseThrow()),
                "consecutive cap silences self while the obligation is still open");

        ctx.percepts.time += 11; // past the shrunk 10-tick patience

        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));

        Utterance ignored = ctx.speech.saidLines.get(ctx.speech.saidLines.size() - 1);
        assertTrue(ignored.system(), "IGNORED is written by the world, not chosen by a chooser");
        assertEquals(SpeechActs.IGNORED.key(), ignored.act());
        assertEquals(otherId.asPerson().toString(), ignored.payload().get(Utterance.SUBJECT));
        assertTrue(journaled(ctx, "gave up waiting"));
        assertTrue(ctx.speech.closedRecords.get(0).closed(), "IGNORED ends() — the engine closes it");
    }

    // ── rule 5: cancel releases nothing — the record survives ───────────────────────────────

    @Test
    @DisplayName("cancel releases nothing — the record stays open for patience or staleness to end")
    void cancelLeavesTheRecordOpen() {
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;
        converse.tick(ctx);

        converse.cancel(ctx);

        Encounter e = ctx.speech.current().orElseThrow();
        assertFalse(e.closed(), "cancel must not close the record");
    }

    // ── rule 6: describe() ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("describe reads converse")
    void describeReadsConverse() {
        assertEquals("converse", new Converse(otherId, Speech.Opening.QUIET).describe());
    }

    // ── the full scripted two-party conversation ─────────────────────────────────────────────

    @Test
    @DisplayName("a full scripted two-party conversation: both tasks SUCCEED, HAIL through END_CHAT")
    void fullTwoPartyConversationEndsInSuccessForBoth() {
        FakeContext other = new FakeContext();
        // The second body's speech over the first's roster — the shared-roster constructor
        // added for exactly this two-party case.
        FakeSpeech otherSpeech = new FakeSpeech(other.self, () -> other.profile,
                () -> other.percepts.time, ctx.speech.roster);
        BrainContext otherContext = new SecondSpeaker(other, otherSpeech);

        Converse taskA = new Converse(BeingId.of(other.self), Speech.Opening.I_HAILED);
        Converse taskB = new Converse(BeingId.of(ctx.self), Speech.Opening.THEY_HAILED);
        ctx.speech.chooser = scripted(Chooser.Line.of(SpeechActs.GREETING),
                Chooser.Line.of(SpeechActs.REQUEST_END_CHAT));
        otherSpeech.chooser = scripted(Chooser.Line.of(SpeechActs.GREETING),
                Chooser.Line.of(SpeechActs.END_CHAT));

        ctx.percepts.time = 0;
        other.percepts.time = 0;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A hails and greets");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B waits out the reply grace");

        ctx.percepts.time = 20;
        other.percepts.time = 20;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A already spoke twice running — silent");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B's grace has elapsed — greets back");

        ctx.percepts.time = 40;
        other.percepts.time = 40;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A's grace has elapsed — proposes ending");
        assertEquals(TaskStatus.SUCCESS, taskB.tick(otherContext), "B accepts — END_CHAT closes the record");
        assertEquals(TaskStatus.SUCCESS, taskA.tick(ctx),
                "the SHARED record now reads closed, though A's own engine never closed it");

        Encounter e = ctx.speech.roster.closed().get(0);
        List<String> acts = e.transcript().stream().map(Utterance::act).toList();
        assertEquals(List.of(SpeechActs.HAIL.key(), SpeechActs.GREETING.key(), SpeechActs.GREETING.key(),
                SpeechActs.REQUEST_END_CHAT.key(), SpeechActs.END_CHAT.key()), acts,
                "the hail out front, then GREETING through END_CHAT");
        assertTrue(e.closed());
        assertEquals(40L, e.closedAt());

        // Task 5's review finding: closing on a SHARED roster notifies only the closer's own
        // engine. B's say() is what actually closed the record, so only B's listener saw it —
        // A's closedRecords stays empty even though A's own task also ends in SUCCESS.
        assertEquals(1, otherSpeech.closedRecords.size(), "B's own engine did the closing");
        assertTrue(ctx.speech.closedRecords.isEmpty(),
                "A never closed anything itself — it only observed the shared record already closed");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** Says each line in order, then falls silent — a deterministic script for one test. */
    private static Chooser scripted(Chooser.Line... lines) {
        Deque<Chooser.Line> queue = new ArrayDeque<>(List.of(lines));
        return (c, turn) -> queue.isEmpty() ? null : queue.poll();
    }

    private static boolean journaled(FakeContext ctx, String detail) {
        return ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .anyMatch(entry -> entry.category() == Category.BRAIN && "converse".equals(entry.event())
                        && entry.detail().equals(detail));
    }

    /**
     * The second party's whole {@link BrainContext}: everything but {@link #speech()} borrowed
     * from a private {@link FakeContext}, since that field is {@code final} and cannot be pointed
     * at a roster shared with a different body.
     */
    private static final class SecondSpeaker implements BrainContext {
        private final FakeContext body;
        private final Speech speech;

        SecondSpeaker(FakeContext body, Speech speech) {
            this.body = body;
            this.speech = speech;
        }

        @Override
        public ActuatorAccess actuators() {
            return body.actuators();
        }

        @Override
        public Percepts percepts() {
            return body.percepts();
        }

        @Override
        public AgentJournal journal() {
            return body.journal();
        }

        @Override
        public Pronouns pronouns() {
            return body.pronouns();
        }

        @Override
        public AgentProfile profile() {
            return body.profile();
        }

        @Override
        public AgentKnowledge knowledge() {
            return body.knowledge();
        }

        @Override
        public Speech speech() {
            return speech;
        }

        @Override
        public double costTolerance() {
            return body.costTolerance();
        }

        @Override
        public RandomGenerator random() {
            return body.random();
        }
    }
}
