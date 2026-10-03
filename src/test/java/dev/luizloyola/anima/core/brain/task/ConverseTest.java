package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
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
import dev.luizloyola.anima.core.social.speech.Picker;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.SpeechEngine;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
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

    /**
     * An ordinary obliging ask with no constrained responses, registered once per JVM (the
     * registry is shared across suites). The snub tests need one now that Anima's only obliging
     * word of its own, the goodbye, ends a record rather than snubbing anybody.
     */
    private static final SpeechAct ASKS = registerAsk();

    private static SpeechAct registerAsk() {
        SpeechAct act = new SpeechAct("converse_test_ask", "anima.speech.test.converse_ask", 1,
                true, true, false, false, List.of());
        try {
            return SpeechActs.register(act);
        } catch (IllegalStateException alreadyRegistered) {
            return SpeechActs.byKey("converse_test_ask").orElseThrow();
        }
    }

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
        Encounter existing = ctx.speech.join(otherId, Speech.Opening.THEY_HAILED).orElseThrow();
        // A different opening than the pre-existing record's — proof current() wins, not join().
        Converse converse = new Converse(otherId, Speech.Opening.I_HAILED);
        ctx.speech.chooser = (c, turn) -> null;

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));

        assertSame(existing, ctx.speech.current().orElseThrow(), "the same record, not a second one");
        assertEquals(1, existing.transcript().size(), "still just the one THEY_HAILED hail — no re-prefill");
        assertEquals(otherId.asPerson(), existing.transcript().get(0).author());
    }

    @Test
    @DisplayName("a counterpart already talking to somebody else fails the task on its first tick")
    void aBusyCounterpartFailsBeforeAnythingIsSaidOrWalked() {
        Being counterpart = FakePercepts.personAt(new Pos(20, 64, 0), 20.0, "Rex");
        ctx.percepts.beings = List.of(counterpart);
        // Their conversation with a third party, seated straight on the shared roster.
        Encounter theirs =
                ctx.speech.roster.join(AgentId.random(), counterpart.id().asPerson(), 0L).orElseThrow();
        Converse converse = new Converse(counterpart.id(), Speech.Opening.I_HAILED);
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.GREETING); // would speak if it could

        assertEquals(TaskStatus.FAILED, converse.tick(ctx));

        assertTrue(journaled(ctx, "they were already talking"));
        assertTrue(ctx.speech.current().isEmpty(), "no record was opened for this body");
        assertEquals(List.of(theirs), ctx.speech.roster.open(), "theirs is the only conversation there is");
        assertTrue(ctx.speech.saidLines.isEmpty(), "not a word, not even a hail prefill");
        assertEquals(0, ctx.mover.moveToCalls, "and no leg ordered toward somebody it will not talk to");
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
        assertEquals(64 + Being.HUMANOID_EYE_HEIGHT, ctx.gazer.y, 1e-9,
                "their OWN eye height, so a talk with anything but a person still lands on a face");
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

    // ── closing the distance: taking over walks into range before a word is said ────────────

    @Test
    @DisplayName("beyond chat range: no line is said, and the mover is asked to walk toward them")
    void beyondChatRangeWalksInsteadOfSpeaking() {
        BeingId counterpartId = BeingId.of(AgentId.random());
        // TestSpecies' chat radius is 12 — 20 blocks is well outside it.
        ctx.percepts.beings = List.of(FakePercepts.personAt(counterpartId, new Pos(20, 64, 0), 20.0, "Rex"));
        Converse converse = new Converse(counterpartId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.GREETING); // would speak if it could

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));

        assertTrue(ctx.speech.saidLines.isEmpty(), "too far to speak this tick");
        assertEquals(1, ctx.mover.moveToCalls, "the mover was asked to move toward them");
        assertEquals(20, ctx.mover.lastX);
        assertEquals(64, ctx.mover.lastY);
        assertEquals(0, ctx.mover.lastZ);
    }

    @Test
    @DisplayName("stepping within chat range resumes speech")
    void steppingWithinChatRangeResumesSpeech() {
        BeingId counterpartId = BeingId.of(AgentId.random());
        ctx.percepts.beings = List.of(FakePercepts.personAt(counterpartId, new Pos(20, 64, 0), 20.0, "Rex"));
        Converse converse = new Converse(counterpartId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.GREETING);

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertTrue(ctx.speech.saidLines.isEmpty(), "still out of range on the first tick");

        // Shrunk to well within TestSpecies' 12-block chat radius.
        ctx.percepts.beings = List.of(FakePercepts.personAt(counterpartId, new Pos(6, 64, 0), 6.0, "Rex"));
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));

        assertEquals(1, ctx.speech.saidLines.size(), "within range now — the chosen line is said");
        assertEquals(SpeechActs.GREETING.key(), ctx.speech.saidLines.get(0).act());
    }

    @Test
    @DisplayName("cancelling mid-walk stops the mover, and the next tick re-evaluates cleanly")
    void cancellingWhileClosingDistanceStopsTheWalk() {
        BeingId counterpartId = BeingId.of(AgentId.random());
        ctx.percepts.beings = List.of(FakePercepts.personAt(counterpartId, new Pos(20, 64, 0), 20.0, "Rex"));
        Converse converse = new Converse(counterpartId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.GREETING);

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertEquals(1, ctx.mover.moveToCalls);
        assertEquals(0, ctx.mover.stopCalls);

        converse.cancel(ctx);

        assertEquals(1, ctx.mover.stopCalls, "the walk toward them is stopped, not left running");

        // Still out of range — a later tick (a fresh grant, say) must re-evaluate cleanly rather
        // than resuming — or crashing on — the walk cancel() already dropped.
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertEquals(2, ctx.mover.moveToCalls, "a fresh walk order, not a stale one");
        assertTrue(ctx.speech.saidLines.isEmpty(), "still too far to speak");
    }

    // ── reached you once is enough: no chasing, no fruitless trailing ───────────────────────

    @Test
    @DisplayName("once they have been in range, walking off is declining — no chase, then IGNORED")
    void inRangeOnceThenBeyondItNeverWalksAgainAndCloses() {
        BeingId counterpartId = BeingId.of(AgentId.random());
        ctx.percepts.beings = List.of(
                FakePercepts.personAt(counterpartId, new Pos(6, 64, 0), 6.0, "Rex"));
        Converse converse = new Converse(counterpartId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null; // silence: this is about the legs, not the lines

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "within range — contact made");
        assertEquals(0, ctx.mover.moveToCalls);

        // They walk off across the field. TestSpecies' chat radius is 12.
        ctx.percepts.beings = List.of(
                FakePercepts.personAt(counterpartId, new Pos(30, 64, 0), 30.0, "Rex"));
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertEquals(0, ctx.mover.moveToCalls, "reached once is enough — nothing follows them");
        assertTrue(ctx.speech.saidLines.isEmpty(), "and nothing is shouted after them either");

        ctx.percepts.time += ctx.profile.i(ProfileAspect.SOCIAL_PATIENCE_TICKS) + 1;
        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));

        assertEquals(0, ctx.mover.moveToCalls, "still nothing — not one step, ever");
        Utterance ignored = ctx.speech.saidLines.get(ctx.speech.saidLines.size() - 1);
        assertTrue(ignored.system());
        assertEquals(SpeechActs.IGNORED.key(), ignored.act());
        assertEquals(counterpartId.asPerson().toString(), ignored.payload().get(Utterance.SUBJECT));
        assertTrue(journaled(ctx, "read the distancing and let them go"));
        assertTrue(ctx.speech.closedRecords.get(0).closed(), "IGNORED ends() — the engine closes it");
    }

    @Test
    @DisplayName("never in range and retreating as fast as the walk: gives up on the second dud leg")
    void twoLegsThatCloseNoDistanceEndTheErrand() {
        BeingId counterpartId = BeingId.of(AgentId.random());
        Converse converse = new Converse(counterpartId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;

        // Each tick they take a step away as this body takes one toward them: the gap never
        // shrinks, and the cell moving is what re-issues the leg (and so prices the last one).
        retreatTo(counterpartId, new Pos(20, 64, 0), 20.0);
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "the first leg is free — nothing to price yet");
        assertEquals(1, ctx.mover.moveToCalls);

        retreatTo(counterpartId, new Pos(21, 64, 0), 21.0);
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "one dud leg is not a verdict");
        assertEquals(2, ctx.mover.moveToCalls);

        retreatTo(counterpartId, new Pos(22, 64, 0), 22.0);
        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));

        assertEquals(2, ctx.mover.moveToCalls, "the second verdict stops the legs, it does not re-order them");
        assertEquals(1, ctx.mover.stopCalls,
                "and it STOPS them: a task that succeeds still owning a move order is the bug "
                        + "this branch exists to fix, walking after somebody it just let go of");
        Utterance ignored = ctx.speech.saidLines.get(ctx.speech.saidLines.size() - 1);
        assertEquals(SpeechActs.IGNORED.key(), ignored.act());
        assertEquals(counterpartId.asPerson().toString(), ignored.payload().get(Utterance.SUBJECT));
        assertTrue(journaled(ctx, "they were headed somewhere else"));
    }

    @Test
    @DisplayName("a leg that gains ground clears the count — following somebody slower never gives up")
    void groundGainedResetsTheFruitlessCount() {
        BeingId counterpartId = BeingId.of(AgentId.random());
        Converse converse = new Converse(counterpartId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;

        retreatTo(counterpartId, new Pos(20, 64, 0), 20.0);
        converse.tick(ctx);
        retreatTo(counterpartId, new Pos(21, 64, 0), 21.0);
        converse.tick(ctx); // one dud
        retreatTo(counterpartId, new Pos(19, 64, 0), 19.0);
        converse.tick(ctx); // ground gained — back to zero
        retreatTo(counterpartId, new Pos(20, 64, 0), 20.0);

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx),
                "one dud since the last gain, not two — this is a slow pursuit, not a lost one");
        assertEquals(4, ctx.mover.moveToCalls, "still walking, a fresh leg per cell they moved to");
        assertTrue(ctx.speech.saidLines.isEmpty());
    }

    @Test
    @DisplayName("a fresh Converse over a record they already spoke into does not re-arm the chase")
    void contactSurvivesTheTaskThatMadeIt() {
        // A preemption (releaseAndClear) or a reload builds a NEW Converse over the SAME record.
        // A latch held in a field comes back false there, and the body sets off after somebody it
        // had already reached — so the latch is read off the transcript instead.
        BeingId counterpartId = BeingId.of(AgentId.random());
        ctx.percepts.beings = List.of(
                FakePercepts.personAt(counterpartId, new Pos(6, 64, 0), 6.0, "Rex"));
        Converse first = new Converse(counterpartId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;
        first.tick(ctx);
        // Them answering is what proves contact — written exactly as their own engine's say()
        // would write it, since on a shared roster only the speaker's engine appends.
        ctx.speech.current().orElseThrow().append(new Utterance(counterpartId.asPerson(),
                SpeechActs.GREETING.key(), Map.of(), ctx.percepts.time));

        ctx.percepts.beings = List.of(
                FakePercepts.personAt(counterpartId, new Pos(30, 64, 0), 30.0, "Rex"));
        Converse second = new Converse(counterpartId, Speech.Opening.QUIET);

        assertEquals(TaskStatus.RUNNING, second.tick(ctx));
        assertEquals(0, ctx.mover.moveToCalls,
                "they were reached once; a new task over the same record must not chase them");
    }

    @Test
    @DisplayName("a hail they shouted is not contact — Answer still closes the last steps")
    void aHailIsNotContact() {
        // THEY_HAILED prefills a line AUTHORED by the counterpart, and a shout is precisely what a
        // body does when it is too far off to talk. Reading it as contact would strand the walk
        // Answer exists to make.
        BeingId counterpartId = BeingId.of(AgentId.random());
        ctx.percepts.beings = List.of(
                FakePercepts.personAt(counterpartId, new Pos(30, 64, 0), 30.0, "Rex"));
        Converse converse = new Converse(counterpartId, Speech.Opening.THEY_HAILED);
        ctx.speech.chooser = (c, turn) -> null;

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));

        assertEquals(1, ctx.mover.moveToCalls, "the answerer walks to whoever called");
    }

    /** Moves the same counterpart, so a re-issue reads as one body stepping rather than a new one. */
    private void retreatTo(BeingId who, Pos at, double distance) {
        ctx.percepts.beings = List.of(FakePercepts.personAt(who, at, distance, "Rex"));
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
    @DisplayName("a chosen line that ends the encounter returns SUCCESS — the answering goodbye")
    void aLineThatEndsTheEncounterSucceeds() {
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "joined, nothing to say yet");
        // Their goodbye lands — said standing here, or a line of theirs with nobody in sight reads
        // as somebody who has walked off. This body's own, a beat later, is the acknowledgement.
        retreatTo(otherId, new Pos(2, 64, 0), 2.0);
        ctx.speech.current().orElseThrow()
                .append(new Utterance(otherId.asPerson(), SpeechActs.END_CHAT.key(), Map.of(), 0));
        ctx.percepts.time = Picker.REPLY_GRACE_TICKS + Converse.JITTER_TICKS;
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

    // ── the beat: the grace floor, plus a jitter rolled per line ────────────────────────────

    @Test
    @DisplayName("a rolled jitter holds the next line past the grace floor, and lets it go after")
    void jitterHoldsTheLinePastTheGraceFloor() {
        ctx.seed(rolling(7)); // this body sits 7 ticks on top of every floor
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.GREETING);

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertEquals(1, ctx.speech.saidLines.size(), "an empty record still opens at once");

        ctx.percepts.time = Picker.REPLY_GRACE_TICKS;
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertEquals(1, ctx.speech.saidLines.size(),
                "the floor is met and the roll is not — the metronome is what this exists to break");

        ctx.percepts.time = Picker.REPLY_GRACE_TICKS + 7;
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertEquals(2, ctx.speech.saidLines.size(), "floor plus the roll — now she speaks");
    }

    @Test
    @DisplayName("a jitter-held tick still notices a snub — waiting on somebody is a different clock")
    void jitterNeverDelaysTheSnubCheck() {
        ctx.speech.caps = new SpeechEngine.Caps(60, 6_000, 1_200, 10); // patience shrunk to 10 ticks
        ctx.seed(rolling(Converse.JITTER_TICKS)); // the widest roll there is — the floor plus 20
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = scripted(Chooser.Line.of(ASKS));

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "asks — the other now owes a reply");

        // Past the floor and the patience, short of the roll: she has nothing to say yet, and that
        // must not buy the other party time on the clock they are already out of.
        ctx.percepts.time = Picker.REPLY_GRACE_TICKS + 5;
        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));

        Utterance ignored = ctx.speech.saidLines.get(ctx.speech.saidLines.size() - 1);
        assertEquals(SpeechActs.IGNORED.key(), ignored.act());
        assertTrue(journaled(ctx, "gave up waiting"));
    }

    // ── rule 4: give up on an expired obligation — IGNORED, journaled, SUCCESS ──────────────

    @Test
    @DisplayName("gives up on an unanswered obligation once patience runs out, and the record closes")
    void givesUpOnAnUnansweredObligation() {
        ctx.speech.caps = new SpeechEngine.Caps(60, 6_000, 1_200, 10); // patience shrunk to 10 ticks
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = scripted(Chooser.Line.of(ASKS), Chooser.Line.of(SpeechActs.DEFLECT));

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "asks — the other now owes a reply");
        assertTrue(ctx.speech.maySpeak(ctx.speech.current().orElseThrow()),
                "one follow-up is hers on the record's terms; the beat she waits is her own");

        // A whole beat — the grace floor plus the widest jitter roll — so the follow-up lands
        // whatever this body rolled. See Converse.JITTER_TICKS.
        ctx.percepts.time += Picker.REPLY_GRACE_TICKS + Converse.JITTER_TICKS;
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "the beat elapsed — one follow-up is hers");

        ctx.percepts.time += Picker.REPLY_GRACE_TICKS + Converse.JITTER_TICKS;
        assertFalse(ctx.speech.maySpeak(ctx.speech.current().orElseThrow()),
                "two lines in a row is the cap — the beat having passed doesn't undo it");

        // 80 ticks of silence now sit on the other party, well past the shrunk 10-tick patience.
        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));

        Utterance ignored = ctx.speech.saidLines.get(ctx.speech.saidLines.size() - 1);
        assertTrue(ignored.system(), "IGNORED is written by the world, not chosen by a chooser");
        assertEquals(SpeechActs.IGNORED.key(), ignored.act());
        assertEquals(otherId.asPerson().toString(), ignored.payload().get(Utterance.SUBJECT));
        assertTrue(journaled(ctx, "gave up waiting"));
        assertTrue(ctx.speech.closedRecords.get(0).closed(), "IGNORED ends() — the engine closes it");
    }

    @Test
    @DisplayName("a goodbye nobody answers closes the record quietly — no IGNORED, no snub")
    void aGoodbyeNobodyAnswersClosesWithoutAVerdict() {
        ctx.speech.caps = new SpeechEngine.Caps(60, 6_000, 1_200, 10); // patience shrunk to 10 ticks
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = scripted(Chooser.Line.of(SpeechActs.END_CHAT));

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx), "said goodbye — the record waits for the answer");
        assertFalse(ctx.speech.current().orElseThrow().closed());

        ctx.percepts.time += Picker.REPLY_GRACE_TICKS + Converse.JITTER_TICKS + 11;
        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));

        Utterance last = ctx.speech.saidLines.get(ctx.speech.saidLines.size() - 1);
        assertEquals(SpeechActs.END_CHAT.key(), last.act(), "the goodbye stays the last line — it is why it ended");
        assertTrue(ctx.speech.closedRecords.get(0).closed());
        assertTrue(journaled(ctx, "left without an answer"));
    }

    @Test
    @DisplayName("a chooser that holds its tongue does not stall the snub clock")
    void aSilentChooserDoesNotStallTheSnubClock() {
        ctx.speech.caps = new SpeechEngine.Caps(60, 6_000, 1_200, 10); // patience shrunk to 10 ticks
        ctx.seed(rolling(0));
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);
        ctx.speech.chooser = scripted(Chooser.Line.of(ASKS)); // asks once, then silence

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));

        // Past the beat and the roll: she may speak, chooses not to, and must still notice the
        // other party is out of time. Returning on the chooser's silence used to skip that.
        ctx.percepts.time += Picker.REPLY_GRACE_TICKS + 11;
        assertTrue(ctx.speech.maySpeak(ctx.speech.current().orElseThrow()),
                "one line of her own is still hers");
        assertEquals(TaskStatus.SUCCESS, converse.tick(ctx));
        assertEquals(SpeechActs.IGNORED.key(),
                ctx.speech.saidLines.get(ctx.speech.saidLines.size() - 1).act());
        assertTrue(journaled(ctx, "gave up waiting"));
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
        FakeSpeech otherSpeech = new FakeSpeech(other.self,
                () -> other.percepts.time, ctx.speech.roster);
        BrainContext otherContext = new SecondSpeaker(other, otherSpeech);

        // Each body perceives the other, standing together. Not decoration: a conversation is a
        // pair in the same place, and once contact is read off the record (see contactMade) a
        // counterpart nobody perceives is somebody who has walked off mid-sentence — which is a
        // different test from this one.
        ctx.percepts.beings = List.of(
                FakePercepts.personAt(BeingId.of(other.self), new Pos(2, 64, 0), 2.0, "B"));
        other.percepts.beings = List.of(
                FakePercepts.personAt(BeingId.of(ctx.self), new Pos(0, 64, 0), 2.0, "A"));

        Converse taskA = new Converse(BeingId.of(other.self), Speech.Opening.I_HAILED);
        Converse taskB = new Converse(BeingId.of(ctx.self), Speech.Opening.THEY_HAILED);
        ctx.speech.chooser = scripted(Chooser.Line.of(SpeechActs.GREETING),
                Chooser.Line.of(SpeechActs.END_CHAT));
        otherSpeech.chooser = scripted(Chooser.Line.of(SpeechActs.GREETING),
                Chooser.Line.of(SpeechActs.END_CHAT));

        // One line per beat, both ways. A beat is REPLY_GRACE_TICKS + JITTER_TICKS: each side rolls
        // its own jitter per line, so only the widest beat is eligible for BOTH of them whatever
        // they rolled — which is what keeps the order of this script deterministic.
        long beat = Picker.REPLY_GRACE_TICKS + Converse.JITTER_TICKS;
        ctx.percepts.time = 0;
        other.percepts.time = 0;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A hails — her greeting waits a beat like any line");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B waits out the same beat");

        ctx.percepts.time = beat;
        other.percepts.time = beat;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A's beat has elapsed — she greets");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "that line restarted B's beat");

        ctx.percepts.time = 2 * beat;
        other.percepts.time = 2 * beat;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A already spoke twice running — silent");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext), "B's beat has elapsed — greets back");

        ctx.percepts.time = 3 * beat;
        other.percepts.time = 3 * beat;
        assertEquals(TaskStatus.RUNNING, taskA.tick(ctx), "A's beat has elapsed — says goodbye");
        assertEquals(TaskStatus.RUNNING, taskB.tick(otherContext),
                "B owes an answer, but an obligation buys no head start on the beat");

        ctx.percepts.time = 4 * beat;
        other.percepts.time = 4 * beat;
        assertEquals(TaskStatus.SUCCESS, taskB.tick(otherContext),
                "B acknowledges — the answering goodbye closes the record");
        assertEquals(TaskStatus.SUCCESS, taskA.tick(ctx),
                "the SHARED record now reads closed, though A's own engine never closed it");

        Encounter e = ctx.speech.roster.closed().get(0);
        List<String> acts = e.transcript().stream().map(Utterance::act).toList();
        assertEquals(List.of(SpeechActs.HAIL.key(), SpeechActs.GREETING.key(), SpeechActs.GREETING.key(),
                SpeechActs.END_CHAT.key(), SpeechActs.END_CHAT.key()), acts,
                "the hail out front, then GREETING through the two goodbyes");
        assertTrue(e.closed());
        assertEquals(4 * beat, e.closedAt());

        // Task 5's review finding: closing on a SHARED roster notifies only the closer's own
        // engine. B's say() is what actually closed the record, so only B's listener saw it —
        // A's closedRecords stays empty even though A's own task also ends in SUCCESS.
        assertEquals(1, otherSpeech.closedRecords.size(), "B's own engine did the closing");
        assertTrue(ctx.speech.closedRecords.isEmpty(),
                "A never closed anything itself — it only observed the shared record already closed");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /**
     * A generator whose {@code nextInt} always answers {@code roll} — deterministic control over
     * the beat's jitter without hand-deriving a seed.
     */
    private static RandomGenerator rolling(int roll) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException("unused by these tests");
            }

            @Override
            public int nextInt(int bound) {
                return roll;
            }
        };
    }

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

    @Test
    @DisplayName("closing in down a hill walks on the floor, one order while they stand")
    void closingInDownAHillWalksOnTheFloor() {
        BeingId counterpartId = BeingId.of(AgentId.random());
        ctx.percepts.fallsAwayFrom(3, 4);
        ctx.percepts.beings = List.of(FakePercepts.personAt(counterpartId, new Pos(20, 64, 0), 20.0, "Rex"));
        Converse converse = new Converse(counterpartId, Speech.Opening.QUIET);
        ctx.speech.chooser = (c, turn) -> null;

        converse.tick(ctx);
        converse.tick(ctx);

        assertEquals(1, ctx.mover.moveToCalls);
        assertEquals(60, ctx.mover.lastY, "the legs lower a goal one cell at most (Luiz, 2026-10-02)");
    }
}
