package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the turn rules from the task brief against the worked alice/bob transcript: obligation
 * discharge, the reply grace, the consecutive-line cap, constrained responses and the turn cap.
 */
class PickerTest {

    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();

    /**
     * A test-only act with a non-empty response set, registered once per JVM. The registry is
     * global and shared with sibling test classes, so a rerun in the same JVM must not throw on
     * a second registration.
     */
    private static final SpeechAct Q_CONSTRAINED = registerConstrainedTestAct();

    private static SpeechAct registerConstrainedTestAct() {
        SpeechAct act = new SpeechAct("q_constrained", "anima.speech.test.q_constrained", 1, true,
                true, false, false, List.of(SpeechActs.GREETING.key()));
        try {
            return SpeechActs.register(act);
        } catch (IllegalStateException alreadyRegistered) {
            return SpeechActs.byKey("q_constrained").orElseThrow();
        }
    }

    private Encounter fresh() {
        return new Encounter(UUID.randomUUID(), List.of(alice, bob), 0L);
    }

    private static Utterance line(AgentId who, SpeechAct act, long tick) {
        return new Utterance(who, act.key(), Map.of(), tick);
    }

    @Test
    @DisplayName("an empty encounter lets either party speak, offering greeting and a goodbye but never the hail")
    void emptyTranscriptOffersGreetingOnly() {
        Encounter e = fresh();
        assertTrue(Picker.maySpeak(e, alice), "nobody has spoken — either party may open");
        assertTrue(Picker.maySpeak(e, bob), "nobody has spoken — either party may open");

        List<SpeechAct> applicable = Picker.applicable(e, alice, 10);
        assertTrue(applicable.contains(SpeechActs.GREETING));
        assertFalse(applicable.contains(SpeechActs.HAIL), "the hail is prefilled at open, never chosen");
        assertTrue(applicable.contains(SpeechActs.END_CHAT), "a goodbye is always on offer — leaving needs no permission");
    }

    @Test
    @DisplayName("after alice's greeting, bob may answer on the very tick — the pause is his to take, not the record's")
    void aReplyMayLandAtOnce() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.GREETING, 100));

        assertTrue(Picker.maySpeak(e, bob), "a quick answer is never refused; a slow one is the replier's manner");
    }

    @Test
    @DisplayName("alice may follow up her own line once, but a second consecutive line ends her turn")
    void maxConsecutiveCapsSelfFollowUp() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.GREETING, 100));
        assertTrue(Picker.maySpeak(e, alice), "one line in a row still leaves a follow-up");

        e.append(line(alice, SpeechActs.DEFLECT, 120));
        assertFalse(Picker.maySpeak(e, alice), "two lines in a row is the cap");
    }

    @Test
    @DisplayName("a goodbye obliges bob, who may acknowledge it at once")
    void obligingAskMayBeAnsweredAtOnce() {
        Encounter e = fresh();
        Utterance ask = line(alice, SpeechActs.END_CHAT, 100);
        e.append(ask);

        assertEquals(ask, Picker.pendingOn(e, bob).orElseThrow(), "alice's ask is what's pending on bob");
        assertTrue(Picker.maySpeak(e, bob), "an owed answer may come on the tick it was asked");
        assertTrue(Picker.applicable(e, bob, 10).contains(SpeechActs.END_CHAT));
        assertFalse(Picker.applicable(e, bob, 10).contains(SpeechActs.GREETING),
                "a goodbye constrains the answer to a goodbye");
    }

    @Test
    @DisplayName("bob answering with any line discharges what was pending on him")
    void selfLineAfterAskDischarges() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.END_CHAT, 100));
        e.append(line(bob, SpeechActs.GREETING, 110));

        assertTrue(Picker.pendingOn(e, bob).isEmpty(),
                "bob's own line discharges the obligation, even outside a response set");
    }

    @Test
    @DisplayName("a constrained obligation narrows applicable to its declared responses, plus the goodbye")
    void constrainedObligationNarrowsApplicable() {
        Encounter e = fresh();
        e.append(line(alice, Q_CONSTRAINED, 100));

        List<SpeechAct> applicable = Picker.applicable(e, bob, 10);
        assertTrue(applicable.contains(SpeechActs.GREETING), "greeting is q_constrained's only response");
        assertFalse(applicable.contains(SpeechActs.DEFLECT));
        assertTrue(applicable.contains(SpeechActs.END_CHAT), "a goodbye is on offer under any constraint");
        assertFalse(applicable.contains(Q_CONSTRAINED), "q_constrained is not among its own responses");
    }

    @Test
    @DisplayName("expiredObligation reports the other party once their unanswered ask outlives patience")
    void expiredObligationNoticesTheSnub() {
        Encounter e = fresh();
        Utterance ask = line(alice, Q_CONSTRAINED, 1000);
        e.append(ask);

        assertEquals(Optional.of(bob), Picker.expiredObligation(e, alice, ask.tick() + 301, 300),
                "301 ticks of silence past a 300-tick patience is a snub");
        assertEquals(Optional.empty(), Picker.expiredObligation(e, alice, ask.tick() + 299, 300),
                "299 ticks hasn't yet crossed the patience threshold");
    }

    @Test
    @DisplayName("a request for food is waited on twice a question's patience — thirty seconds at 300")
    void aRequestForFoodIsWaitedOnTwiceAsLong() {
        Encounter e = fresh();
        Utterance ask = line(alice, SpeechActs.ASK_FOOD, 1000);
        e.append(ask);

        assertEquals(Optional.empty(), Picker.expiredObligation(e, alice, ask.tick() + 580, 300),
                "29 seconds is still within a request's patience");
        assertEquals(Optional.empty(), Picker.expiredObligation(e, alice, ask.tick() + 600, 300),
                "patience is inclusive");
        assertEquals(Optional.of(bob), Picker.expiredObligation(e, alice, ask.tick() + 601, 300),
                "past 30 seconds the request lapses as a snub");
        assertEquals(600L, Picker.patienceFor(ask, 300));
        assertEquals(300L, Picker.patienceFor(line(alice, Q_CONSTRAINED, 0), 300),
                "a question keeps its own patience");
    }

    @Test
    @DisplayName("at the turn cap, applicable narrows to only ending the conversation")
    void turnCapNarrowsToEndingActsOnly() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.GREETING, 100));
        e.append(line(bob, SpeechActs.GREETING, 120));
        e.append(line(alice, SpeechActs.DEFLECT, 140));
        e.append(line(bob, SpeechActs.DEFLECT, 160));
        e.append(line(alice, SpeechActs.GREETING, 180));

        List<SpeechAct> applicable = Picker.applicable(e, bob, 5);
        assertTrue(applicable.contains(SpeechActs.END_CHAT));
        assertFalse(applicable.contains(SpeechActs.GREETING), "past the cap only an ending is on offer");
        assertFalse(applicable.contains(SpeechActs.DEFLECT));
    }

    @Test
    @DisplayName("explain covers every registered act, agrees with applicable, and always says why")
    void explainIsTheWholeVocabularyWithReasons() {
        Encounter e = fresh();
        e.append(line(alice, Q_CONSTRAINED, 100));

        List<Picker.Verdict> verdicts = Picker.explain(e, bob, 10);

        assertEquals(SpeechActs.all().size(), verdicts.size(), "every word in the registry gets a verdict");
        assertEquals(Picker.applicable(e, bob, 10),
                verdicts.stream().filter(Picker.Verdict::applicable).map(Picker.Verdict::act).toList(),
                "the readout cannot drift from the filter — applicable() is a filter over explain()");
        for (Picker.Verdict verdict : verdicts) {
            assertFalse(verdict.reason().isBlank(),
                    verdict.act().key() + " was decided without saying why");
        }
        // The reasons name the branch that actually excluded the act, not a generic "no".
        assertEquals("the hail opens a record; it is not said", reasonFor(verdicts, SpeechActs.HAIL));
        assertTrue(reasonFor(verdicts, SpeechActs.STALE).startsWith("a system verdict"));
        assertEquals("constrained by the pending q_constrained → greeting",
                reasonFor(verdicts, SpeechActs.DEFLECT));
        assertEquals("applicable", reasonFor(verdicts, SpeechActs.GREETING));
    }

    @Test
    @DisplayName("at the cap, explain turns everything but the goodbye away and says so")
    void explainNamesTheCapRule() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.GREETING, 100));

        List<Picker.Verdict> capped = Picker.explain(e, bob, 1); // one line is already the cap
        assertEquals("the cap leaves only farewells", reasonFor(capped, SpeechActs.GREETING));
        assertEquals("a goodbye is always on offer", reasonFor(capped, SpeechActs.END_CHAT));
        assertEquals("a goodbye is always on offer",
                reasonFor(Picker.explain(e, bob, 10), SpeechActs.END_CHAT),
                "with room to spare the goodbye is on offer for the same reason");
    }

    @Test
    @DisplayName("a pending goodbye constrains the answer to a goodbye, and nothing else")
    void goodbyePendingConstrainsToTheAcknowledgement() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.END_CHAT, 100));

        assertEquals(List.of(SpeechActs.END_CHAT), Picker.applicable(e, bob, 10),
                "the only answer to a goodbye is a goodbye");
        assertEquals("constrained by the pending end_chat → end_chat",
                reasonFor(Picker.explain(e, bob, 10), SpeechActs.GREETING));
    }

    @Test
    @DisplayName("an unacknowledged goodbye is a lapsed farewell for its author, never a snub")
    void anUnacknowledgedGoodbyeIsNotASnub() {
        Encounter e = fresh();
        Utterance bye = line(alice, SpeechActs.END_CHAT, 1000);
        e.append(bye);

        assertEquals(Optional.empty(), Picker.expiredObligation(e, alice, bye.tick() + 301, 300),
                "bob owes only an acknowledgement — not giving one is leaving, not snubbing");
        assertFalse(Picker.lapsedFarewell(e, alice, bye.tick() + 300, 300), "patience is inclusive");
        assertTrue(Picker.lapsedFarewell(e, alice, bye.tick() + 301, 300),
                "alice's own goodbye has gone unanswered past patience");
        assertFalse(Picker.lapsedFarewell(e, bob, bye.tick() + 301, 300),
                "the goodbye is alice's to give up on, not bob's");
    }

    private static String reasonFor(List<Picker.Verdict> verdicts, SpeechAct act) {
        return verdicts.stream().filter(v -> v.act() == act).findFirst().orElseThrow().reason();
    }

    // ── unanswered: a body that never came ───────────────────────────────────────────────────

    @Test
    @DisplayName("unanswered: nothing from the other party and the record older than patience")
    void unansweredPastPatience() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.HAIL, 0L));

        assertFalse(Picker.unanswered(e, bob, 300L, 300), "patience is inclusive");
        assertTrue(Picker.unanswered(e, bob, 301L, 300));
    }

    @Test
    @DisplayName("unanswered: one spoken line from the other party clears it for good")
    void unansweredClearedByAnyLine() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.HAIL, 0L));
        e.append(line(bob, SpeechActs.GREETING, 10L));

        assertFalse(Picker.unanswered(e, bob, 10_000L, 300));
    }

    @Test
    @DisplayName("unanswered: the other party's own hail is an opener, not an answer")
    void unansweredIgnoresTheirHail() {
        Encounter e = fresh();
        e.append(line(bob, SpeechActs.HAIL, 0L));

        assertTrue(Picker.unanswered(e, bob, 301L, 300));
    }

    @Test
    @DisplayName("unanswered: a system line moves the clock but is nobody's answer")
    void unansweredSystemLineIsNotAnAnswer() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.HAIL, 0L));
        e.append(Utterance.system(SpeechActs.IGNORED.key(), alice, 200L));

        assertFalse(Picker.unanswered(e, bob, 400L, 300), "measured from the last activity");
        assertTrue(Picker.unanswered(e, bob, 501L, 300));
    }
}
