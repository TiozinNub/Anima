package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a real {@link SpeechEngine} — no mock roster, no mock picker — with two {@link AgentId}s
 * and a manual clock, pinning the semantics the task brief calls out as what Task 6's
 * {@code Converse} relies on: the hail prefill, notification, idempotency, staleness, the
 * duration cap, and {@link Choosers#BASIC} reading a real {@link Chooser.Turn}.
 */
class SpeechEngineTest {

    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();

    /** {@link FakeSpeech}'s own defaults — plenty of room unless a test narrows one on purpose. */
    private static final SpeechEngine.Caps CAPS = new SpeechEngine.Caps(60, 6_000, 1_200, 300);

    /**
     * A test-only act with a non-empty response set, registered once per JVM — same defensive
     * shape {@code PickerTest} uses, since the registry is global and shared across test classes.
     */
    private static final SpeechAct ASK_WITH_ONLY_GREETING_AS_RESPONSE = registerAskAct();

    private static SpeechAct registerAskAct() {
        SpeechAct act = new SpeechAct("engine_test_ask", "anima.speech.test.engine_test_ask", 1,
                true, true, false, false, List.of(SpeechActs.GREETING.key()));
        try {
            return SpeechActs.register(act);
        } catch (IllegalStateException alreadyRegistered) {
            return SpeechActs.byKey("engine_test_ask").orElseThrow();
        }
    }

    /** Records every {@code said}/{@code closed} notification, in order, for direct assertions. */
    private static final class Recorder implements SpeechEngine.Listener {
        final List<Utterance> said = new ArrayList<>();
        final List<Encounter> closed = new ArrayList<>();

        @Override
        public void said(Encounter e, Utterance u) {
            said.add(u);
        }

        @Override
        public void closed(Encounter e) {
            closed.add(e);
        }
    }

    private SpeechEngine engineFor(AgentId self, Encounters roster, long[] clock,
            SpeechEngine.Caps caps, Recorder recorder) {
        return new SpeechEngine(self, () -> clock[0], roster,
                () -> Choosers.BASIC, () -> caps, recorder);
    }

    @Test
    @DisplayName("join credited I_HAILED prefills the hail authored by self, once")
    void joinPrefillsHailAuthoredBySelfWhenIHailed() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine engine = engineFor(alice, roster, clock, CAPS, recorder);

        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.I_HAILED).orElseThrow();

        assertEquals(1, e.transcript().size(), "the hail is the only line prefilled on open");
        Utterance hail = e.transcript().get(0);
        assertEquals(alice, hail.author(), "I_HAILED credits self as the hailer");
        assertEquals(SpeechActs.HAIL.key(), hail.act());
        assertEquals(1, recorder.said.size(), "the prefill notifies the listener exactly once");

        Encounter rejoined = engine.join(BeingId.of(bob), Speech.Opening.THEY_HAILED).orElseThrow();
        assertSame(e, rejoined, "the pair already has an open record — join must not duplicate it");
        assertEquals(1, e.transcript().size(),
                "a non-empty transcript means the record wasn't just created — no second prefill");
        assertEquals(1, recorder.said.size(), "no further notification for the no-op rejoin");
    }

    @Test
    @DisplayName("join credited THEY_HAILED prefills the hail authored by the other party")
    void joinPrefillsHailAuthoredByOtherWhenTheyHailed() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        // Alice's own engine calls join, but the credited hailer is bob — the record reads the
        // same regardless of whose engine happened to make the call.
        SpeechEngine engine = engineFor(alice, roster, clock, CAPS, recorder);

        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.THEY_HAILED).orElseThrow();

        Utterance hail = e.transcript().get(0);
        assertEquals(bob, hail.author(), "THEY_HAILED credits the other party as the hailer");
        assertEquals(SpeechActs.HAIL.key(), hail.act());
    }

    @Test
    @DisplayName("join credited QUIET prefills nothing")
    void joinWithQuietOpeningPrefillsNothing() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine engine = engineFor(alice, roster, clock, CAPS, recorder);

        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.QUIET).orElseThrow();

        assertTrue(e.transcript().isEmpty(), "a quiet meeting starts no record of anyone hailing");
        assertTrue(recorder.said.isEmpty());
    }

    @Test
    @DisplayName("join against a body already talking is refused, and prefills no hail")
    void joinAgainstABusyBodyIsRefused() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine engine = engineFor(alice, roster, clock, CAPS, recorder);
        AgentId carol = AgentId.random();
        Encounter theirs = roster.join(bob, carol, 0L).orElseThrow(); // bob is spoken for

        Optional<Encounter> refused = engine.join(BeingId.of(bob), Speech.Opening.I_HAILED);

        assertTrue(refused.isEmpty(), "a bystander may not open a second record against a busy body");
        assertEquals(List.of(theirs), roster.open(), "and no record was created for the attempt");
        assertTrue(recorder.said.isEmpty(), "a refused join credits no hail to anybody");
    }

    @Test
    @DisplayName("say appends authored self, notifies the listener, and END_CHAT closes the record")
    void sayAppendsNotifiesAndEndChatCloses() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine engine = engineFor(alice, roster, clock, CAPS, recorder);
        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.QUIET).orElseThrow();

        engine.say(e, Chooser.Line.of(SpeechActs.GREETING));

        assertEquals(1, e.transcript().size());
        Utterance greeting = e.transcript().get(0);
        assertEquals(alice, greeting.author());
        assertEquals(SpeechActs.GREETING.key(), greeting.act());
        assertEquals(1, recorder.said.size(), "say notifies the listener");
        assertFalse(e.closed(), "greeting doesn't end the conversation");
        assertTrue(engine.turn(e).greeted(), "a non-system GREETING of self's own is now in the transcript");

        engine.say(e, Chooser.Line.of(SpeechActs.END_CHAT));

        assertTrue(e.closed(), "END_CHAT ends the record");
        assertEquals(1, recorder.closed.size(), "the close is notified exactly once");
        assertTrue(roster.closed().contains(e), "closing the record moves it off the roster's open list");
    }

    @Test
    @DisplayName("system is a no-op the second time for the same (act, subject) pair, and ends when the act ends")
    void systemLineIsIdempotentPerActAndSubject() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine engine = engineFor(alice, roster, clock, CAPS, recorder);
        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.QUIET).orElseThrow();

        engine.system(e, SpeechActs.IGNORED, bob);
        assertEquals(1, e.transcript().size());
        assertTrue(e.hasSystem(SpeechActs.IGNORED.key(), bob));
        assertTrue(e.closed(), "IGNORED ends the record");
        assertEquals(1, recorder.closed.size());

        engine.system(e, SpeechActs.IGNORED, bob);

        assertEquals(1, e.transcript().size(), "a repeat notice for the same subject writes nothing new");
        assertEquals(1, recorder.said.size(), "no further said() notification either");
        assertEquals(1, recorder.closed.size(), "close is not re-notified on the no-op repeat");
    }

    @Test
    @DisplayName("current closes a stale encounter with a SYSTEM STALE line and returns empty")
    void currentClosesStaleEncounterWithStaleLine() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine.Caps tightGap = new SpeechEngine.Caps(60, 6_000, 100, 300);
        SpeechEngine engine = engineFor(alice, roster, clock, tightGap, recorder);
        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.QUIET).orElseThrow();

        clock[0] = 101L; // 101 ticks of silence past a 100-tick gap

        Optional<Encounter> current = engine.current();

        assertTrue(current.isEmpty(), "a stale encounter is never handed back as current");
        assertEquals(1, e.transcript().size());
        Utterance stale = e.transcript().get(0);
        assertTrue(stale.system(), "the noticing is written by the world, not a party");
        assertEquals(SpeechActs.STALE.key(), stale.act());
        assertEquals(alice.toString(), stale.payload().get(Utterance.SUBJECT),
                "the subject is self — whoever's current() noticed the staleness");
        assertTrue(e.closed());
        assertTrue(roster.closed().contains(e));
        assertEquals(1, recorder.closed.size());
    }

    @Test
    @DisplayName("current returns the open encounter untouched when it is not stale")
    void currentReturnsOpenEncounterWhenNotStale() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine.Caps tightGap = new SpeechEngine.Caps(60, 6_000, 100, 300);
        SpeechEngine engine = engineFor(alice, roster, clock, tightGap, recorder);
        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.QUIET).orElseThrow();

        clock[0] = 99L; // exactly at the gap — Encounters.stale is strictly-greater-than

        Optional<Encounter> current = engine.current();

        assertEquals(Optional.of(e), current);
        assertTrue(e.transcript().isEmpty(), "nothing was written — the record is untouched");
        assertFalse(e.closed());
    }

    @Test
    @DisplayName("past the tick cap, turn's applicable set narrows to ending acts only")
    void tickCapForcesApplicableDownToEndingActsOnly() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine.Caps shortDuration = new SpeechEngine.Caps(60, 50, 6_000, 300);
        SpeechEngine engine = engineFor(alice, roster, clock, shortDuration, recorder);
        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.QUIET).orElseThrow();

        List<SpeechAct> beforeCap = engine.turn(e).applicable();
        assertTrue(beforeCap.contains(SpeechActs.GREETING), "well within the duration cap, greeting is on offer");

        clock[0] = 51L; // 51 ticks since opening at 0 exceeds a 50-tick duration cap

        List<SpeechAct> afterCap = engine.turn(e).applicable();
        assertTrue(afterCap.contains(SpeechActs.REQUEST_END_CHAT));
        assertTrue(afterCap.contains(SpeechActs.END_CHAT));
        assertFalse(afterCap.contains(SpeechActs.GREETING), "past the duration cap only an ending is on offer");
        assertFalse(afterCap.contains(SpeechActs.DEFLECT));
    }

    @Test
    @DisplayName("maySpeak and expiredObligation delegate to Picker with self, now and caps' patience")
    void maySpeakAndExpiredObligationDelegateToPicker() {
        Encounters roster = new Encounters();
        long[] clock = {1_000L};
        Recorder recorder = new Recorder();
        SpeechEngine.Caps shortPatience = new SpeechEngine.Caps(60, 6_000, 6_000, 300);
        SpeechEngine engine = engineFor(alice, roster, clock, shortPatience, recorder);
        Encounter e = roster.join(alice, bob, 1_000L).orElseThrow();
        // Alice is the one who asked — the obligation to answer now sits on bob, not on her.
        Utterance ask = new Utterance(alice, SpeechActs.REQUEST_END_CHAT.key(), Map.of(), 1_000L);
        e.append(ask);

        assertFalse(engine.maySpeak(e), "her own line has not yet had its beat");
        assertEquals(Picker.maySpeak(e, alice, clock[0]), engine.maySpeak(e));

        clock[0] = 1_000L + Picker.REPLY_GRACE_TICKS;
        assertTrue(engine.maySpeak(e), "the beat elapsed — one follow-up of her own is still hers");
        assertEquals(Picker.maySpeak(e, alice, clock[0]), engine.maySpeak(e));

        clock[0] = 1_000L + 301L; // 301 ticks of silence past a 300-tick patience is a snub
        assertEquals(Optional.of(bob), engine.expiredObligation(e), "bob's obligation has outrun alice's patience");
        assertEquals(Picker.expiredObligation(e, alice, clock[0], shortPatience.patienceTicks()),
                engine.expiredObligation(e));
    }

    @Test
    @DisplayName("BASIC answers a pending constrained ask with its first declared response, via a real Turn")
    void basicChooserAnswersPendingConstrainedAskWithFirstResponse() {
        Encounters roster = new Encounters();
        Encounter e = roster.join(alice, bob, 0L).orElseThrow();
        e.append(new Utterance(bob, ASK_WITH_ONLY_GREETING_AS_RESPONSE.key(), Map.of(), 0L));
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine engine = engineFor(alice, roster, clock, CAPS, recorder);

        Chooser.Turn turn = engine.turn(e);
        assertTrue(turn.pending().isPresent());
        assertEquals(ASK_WITH_ONLY_GREETING_AS_RESPONSE.key(), turn.pending().orElseThrow().act());
        assertEquals(Optional.of(bob), turn.counterpart());

        Chooser.Line line = Choosers.BASIC.choose(new FakeContext(), turn);

        assertNotNull(line, "the pending ask's only declared response is applicable — BASIC must answer it");
        assertEquals(SpeechActs.GREETING, line.act());
    }

    @Test
    @DisplayName("counterpart is the other participant, from either side of the pair")
    void counterpartIsTheOtherParticipant() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine aliceEngine = engineFor(alice, roster, clock, CAPS, recorder);
        SpeechEngine bobEngine = engineFor(bob, roster, clock, CAPS, new Recorder());
        Encounter e = roster.join(alice, bob, 0L).orElseThrow();

        assertEquals(Optional.of(bob), aliceEngine.counterpart(e));
        assertEquals(Optional.of(alice), bobEngine.counterpart(e));
    }

    @Test
    @DisplayName("turn's awaiting carries self's own undischarged ask, and empties once the other replies")
    void turnAwaitingCarriesSelfsUndischargedAskUntilTheOtherReplies() {
        Encounters roster = new Encounters();
        long[] clock = {0L};
        Recorder recorder = new Recorder();
        SpeechEngine engine = engineFor(alice, roster, clock, CAPS, recorder);
        Encounter e = engine.join(BeingId.of(bob), Speech.Opening.QUIET).orElseThrow();

        engine.say(e, Chooser.Line.of(SpeechActs.REQUEST_END_CHAT));

        Chooser.Turn afterAsk = engine.turn(e);
        assertTrue(afterAsk.pending().isEmpty(), "nothing is pending ON alice — her own line is last");
        assertTrue(afterAsk.awaiting().isPresent(), "alice asked bob to end and nothing of his has answered it");
        assertEquals(SpeechActs.REQUEST_END_CHAT.key(), afterAsk.awaiting().orElseThrow().act());

        clock[0] += Picker.REPLY_GRACE_TICKS;
        e.append(new Utterance(bob, SpeechActs.DEFLECT.key(), Map.of(), clock[0]));

        assertTrue(engine.turn(e).awaiting().isEmpty(),
                "any line of bob's discharges what alice was owed an answer to, same as pending's own rule");
    }
}
