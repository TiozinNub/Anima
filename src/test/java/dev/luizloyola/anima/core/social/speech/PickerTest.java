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
    @DisplayName("an empty encounter lets either party speak, offering greeting but never hail or a goodbye")
    void emptyTranscriptOffersGreetingOnly() {
        Encounter e = fresh();
        assertTrue(Picker.maySpeak(e, alice, 0), "nobody has spoken — either party may open");
        assertTrue(Picker.maySpeak(e, bob, 0), "nobody has spoken — either party may open");

        List<SpeechAct> applicable = Picker.applicable(e, alice, 10);
        assertTrue(applicable.contains(SpeechActs.GREETING));
        assertFalse(applicable.contains(SpeechActs.HAIL), "the hail is prefilled at open, never chosen");
        assertFalse(applicable.contains(SpeechActs.END_CHAT), "nothing has proposed ending yet");
    }

    @Test
    @DisplayName("after alice's greeting, bob must wait out the reply grace before he may answer")
    void replyGraceBlocksAnImmediateReply() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.GREETING, 100));

        assertFalse(Picker.maySpeak(e, bob, 105), "5 ticks in is well inside the grace window");
        assertTrue(Picker.maySpeak(e, bob, 120), "20 ticks in, the grace window has fully elapsed");
    }

    @Test
    @DisplayName("alice may follow up her own line once, but a second consecutive line ends her turn")
    void maxConsecutiveCapsSelfFollowUp() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.GREETING, 100));
        assertFalse(Picker.maySpeak(e, alice, 105), "her own follow-up waits the same beat anyone else does");
        assertTrue(Picker.maySpeak(e, alice, 500), "one line in a row still leaves a follow-up");

        e.append(line(alice, SpeechActs.DEFLECT, 120));
        assertFalse(Picker.maySpeak(e, alice, 900),
                "two lines in a row is the cap — long after the grace window doesn't undo it");
    }

    @Test
    @DisplayName("a proposal to end obliges bob, who waits out the beat like anyone else, then may say goodbye")
    void obligingAskStillWaitsOutTheGrace() {
        Encounter e = fresh();
        Utterance ask = line(alice, SpeechActs.REQUEST_END_CHAT, 100);
        e.append(ask);

        assertEquals(ask, Picker.pendingOn(e, bob).orElseThrow(), "alice's ask is what's pending on bob");
        assertFalse(Picker.maySpeak(e, bob, 100),
                "being owed an answer is no licence to answer on the very tick it was asked");
        assertFalse(Picker.maySpeak(e, bob, 119), "one tick short of the beat is still too soon");
        assertTrue(Picker.maySpeak(e, bob, 120), "the beat elapsed — now the owed answer may come");
        assertTrue(Picker.applicable(e, bob, 10).contains(SpeechActs.END_CHAT));
    }

    @Test
    @DisplayName("bob answering with any line discharges what was pending on him")
    void selfLineAfterAskDischarges() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.REQUEST_END_CHAT, 100));
        e.append(line(bob, SpeechActs.GREETING, 110));

        assertTrue(Picker.pendingOn(e, bob).isEmpty(),
                "bob's own line discharges the obligation, even outside a response set");
    }

    @Test
    @DisplayName("a constrained obligation narrows applicable to only its declared responses")
    void constrainedObligationNarrowsApplicable() {
        Encounter e = fresh();
        e.append(line(alice, Q_CONSTRAINED, 100));

        List<SpeechAct> applicable = Picker.applicable(e, bob, 10);
        assertTrue(applicable.contains(SpeechActs.GREETING), "greeting is q_constrained's only response");
        assertFalse(applicable.contains(SpeechActs.DEFLECT));
        assertFalse(applicable.contains(SpeechActs.REQUEST_END_CHAT));
        assertFalse(applicable.contains(SpeechActs.END_CHAT));
        assertFalse(applicable.contains(Q_CONSTRAINED), "q_constrained is not among its own responses");
    }

    @Test
    @DisplayName("expiredObligation reports the other party once their unanswered ask outlives patience")
    void expiredObligationNoticesTheSnub() {
        Encounter e = fresh();
        Utterance ask = line(alice, SpeechActs.REQUEST_END_CHAT, 1000);
        e.append(ask);

        assertEquals(Optional.of(bob), Picker.expiredObligation(e, alice, ask.tick() + 301, 300),
                "301 ticks of silence past a 300-tick patience is a snub");
        assertEquals(Optional.empty(), Picker.expiredObligation(e, alice, ask.tick() + 299, 300),
                "299 ticks hasn't yet crossed the patience threshold");
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
        assertTrue(applicable.contains(SpeechActs.REQUEST_END_CHAT));
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
    @DisplayName("at the cap, explain says which of the two cap rules turned each act away")
    void explainNamesBothCapRules() {
        Encounter e = fresh();
        e.append(line(alice, SpeechActs.GREETING, 100));

        List<Picker.Verdict> capped = Picker.explain(e, bob, 1); // one line is already the cap
        assertEquals("the cap leaves only farewells", reasonFor(capped, SpeechActs.GREETING));
        assertEquals("applicable", reasonFor(capped, SpeechActs.END_CHAT));

        // The same goodbye, off the table for the other reason while the record still has room.
        assertEquals("a goodbye answers a request_end_chat, or a record at its cap",
                reasonFor(Picker.explain(e, bob, 10), SpeechActs.END_CHAT));
    }

    private static String reasonFor(List<Picker.Verdict> verdicts, SpeechAct act) {
        return verdicts.stream().filter(v -> v.act() == act).findFirst().orElseThrow().reason();
    }
}
