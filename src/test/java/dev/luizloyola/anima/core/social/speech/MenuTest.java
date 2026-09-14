package dev.luizloyola.anima.core.social.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The menu is the picker read by a participant who cannot want, narrowed by what they already did
 * and already know: offered is applicable-on-the-beat less the trims of 2026-09-13, and a pick is
 * refused for exactly the reason a player can act on.
 */
class MenuTest {

    private static final int CAP = 60;

    private final AgentId player = AgentId.random();
    private final AgentId body = AgentId.random();
    private final Random random = new Random(7);
    /** A contact book as {@code [knower, whom]} pairs — the menu only ever asks it a question. */
    private final Set<List<AgentId>> book = new HashSet<>();
    private final BiPredicate<AgentId, AgentId> knows =
            (knower, whom) -> book.contains(List.of(knower, whom));

    /**
     * A topic-bearing act, an introduction, and an ask for one — test-only shapes, since Anima
     * itself ships no introducing word. Registered once per JVM: the registry is shared across
     * suites.
     */
    private static final SpeechAct ABOUT = register(new SpeechAct("about_test",
            "anima.speech.test.about", 1, true, false, false, false, List.of(), List.of("rain", "roads")));
    private static final SpeechAct NAME = register(new SpeechAct("name_test",
            "anima.speech.test.name", 1, true, false, true, false, List.of()));
    private static final SpeechAct ASK_NAME = register(new SpeechAct("ask_name_test",
            "anima.speech.test.ask_name", 1, true, true, false, false,
            List.of("name_test", SpeechActs.DEFLECT.key())));

    private static SpeechAct register(SpeechAct act) {
        try {
            return SpeechActs.register(act);
        } catch (IllegalStateException alreadyRegistered) {
            return SpeechActs.byKey(act.key()).orElseThrow();
        }
    }

    private Encounter fresh() {
        return new Encounter(UUID.randomUUID(), List.of(player, body), 0L);
    }

    private static Utterance line(AgentId who, SpeechAct act, long tick) {
        return new Utterance(who, act.key(), Map.of(), tick);
    }

    private List<SpeechAct> offered(Encounter e) {
        return Menu.offered(e, player, CAP, knows);
    }

    private Menu.Pick pick(Encounter e, SpeechAct act, String topic) {
        return Menu.pick(e, player, CAP, knows, act.key(), topic, random);
    }

    // ── what is offered ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an empty record offers at once: a goodbye, small talk, the two name acts — never the hail")
    void emptyRecordOffersAtOnce() {
        List<SpeechAct> offered = offered(fresh());

        assertTrue(offered.containsAll(List.of(SpeechActs.END_CHAT, ABOUT, ASK_NAME, NAME)));
        assertFalse(offered.contains(SpeechActs.HAIL), "the hail opens a record; it is not said");
        assertFalse(offered.contains(SpeechActs.IGNORED), "a system verdict is never on a menu");
    }

    @Test
    @DisplayName("the menu is offered the tick a line of theirs lands — the pause is the replier's, not the record's")
    void offeredAtOnce() {
        Encounter e = fresh();
        e.append(line(body, SpeechActs.GREETING, 100L));

        assertFalse(offered(e).isEmpty());
    }

    @Test
    @DisplayName("trim: never the greeting — the hail was the greeting")
    void neverTheGreeting() {
        Encounter e = fresh();
        assertFalse(offered(e).contains(SpeechActs.GREETING));

        e.append(line(body, SpeechActs.GREETING, 100L));
        assertFalse(offered(e).contains(SpeechActs.GREETING), "not even to greet back");
        assertEquals(Menu.Reason.NOT_OFFERED, pick(e, SpeechActs.GREETING, null).reason());
    }

    @Test
    @DisplayName("trim: saying nothing is only ever the answer to a question")
    void sayingNothingOnlyAnswersAQuestion() {
        Encounter e = fresh();
        assertFalse(offered(e).contains(SpeechActs.DEFLECT), "never as an opener");

        e.append(line(body, ASK_NAME, 100L));
        List<SpeechAct> asked = offered(e);
        assertTrue(asked.contains(SpeechActs.DEFLECT), "the way to not answer");
        assertTrue(asked.contains(NAME), "or to answer");
        assertTrue(asked.contains(SpeechActs.END_CHAT), "or to leave");
        assertFalse(asked.contains(ABOUT), "the ask constrains everything else away");
    }

    @Test
    @DisplayName("trim: an ask for a name only while the name is not in the book")
    void askingForANameOnlyWhileUnknown() {
        Encounter e = fresh();
        assertTrue(offered(e).contains(ASK_NAME));

        book.add(List.of(player, body));
        assertFalse(offered(e).contains(ASK_NAME), "the player knows them — nothing to ask");
        assertEquals(Menu.Reason.NOT_OFFERED, pick(e, ASK_NAME, null).reason());
    }

    @Test
    @DisplayName("trim: an introduction only while they do not know you")
    void introducingOnlyWhileUnknownToThem() {
        Encounter e = fresh();
        assertTrue(offered(e).contains(NAME));

        book.add(List.of(body, player));
        assertFalse(offered(e).contains(NAME), "they know the player — nothing to introduce");
        assertEquals(Menu.Reason.NOT_OFFERED, pick(e, NAME, null).reason());
    }

    @Test
    @DisplayName("trim: an introduction once per record, whatever the book says")
    void introducingOncePerRecord() {
        Encounter e = fresh();
        e.append(line(player, NAME, 100L));

        assertFalse(offered(e).contains(NAME), "already said here");
        assertEquals(Menu.Reason.NOT_OFFERED, pick(e, NAME, null).reason());
    }

    @Test
    @DisplayName("the steady state between acquaintances: small talk and a goodbye")
    void steadyState() {
        Encounter e = fresh();
        book.add(List.of(player, body));
        book.add(List.of(body, player));
        e.append(line(body, SpeechActs.GREETING, 100L));

        List<SpeechAct> offered = offered(e);
        assertTrue(offered.containsAll(List.of(ABOUT, SpeechActs.END_CHAT)));
        assertFalse(offered.contains(SpeechActs.GREETING));
        assertFalse(offered.contains(SpeechActs.DEFLECT));
        assertFalse(offered.contains(ASK_NAME));
        assertFalse(offered.contains(NAME));
    }

    // ── the pick ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a pick of an act the registry does not hold is UNKNOWN")
    void unknownAct() {
        Menu.Pick pick = Menu.pick(fresh(), player, CAP, knows, "no_such_word", null, random);

        assertEquals(Menu.Reason.UNKNOWN, pick.reason());
        assertNull(pick.line());
    }

    @Test
    @DisplayName("a registered act the menu would not offer is NOT_OFFERED, even before the beat")
    void notOfferedOutranksTooSoon() {
        Encounter e = fresh();
        e.append(line(body, SpeechActs.GREETING, 100L));

        // The greeting is off the table whatever the tick, and the beat is unspent too: the
        // refusal must name the reason the player can do something about.
        assertEquals(Menu.Reason.NOT_OFFERED, pick(e, SpeechActs.GREETING, null).reason());
    }

    @Test
    @DisplayName("an offered act is OK with an empty payload")
    void okPlain() {
        Menu.Pick pick = pick(fresh(), SpeechActs.END_CHAT, null);

        assertTrue(pick.ok());
        assertEquals(SpeechActs.END_CHAT, pick.line().act());
        assertTrue(pick.line().payload().isEmpty());
    }

    @Test
    @DisplayName("a topic on an act that carries none is BAD_TOPIC")
    void topicOnTopiclessAct() {
        assertEquals(Menu.Reason.BAD_TOPIC, pick(fresh(), SpeechActs.END_CHAT, "rain").reason());
    }

    @Test
    @DisplayName("a topic-bearing act takes a declared topic and refuses an undeclared one")
    void declaredTopicsOnly() {
        Menu.Pick declared = pick(fresh(), ABOUT, "roads");
        Menu.Pick undeclared = pick(fresh(), ABOUT, "weather");

        assertTrue(declared.ok());
        assertEquals("roads", declared.line().payload().get(Utterance.TOPIC));
        assertEquals(Menu.Reason.BAD_TOPIC, undeclared.reason());
    }

    @Test
    @DisplayName("a topic-bearing act with no topic given draws one of its own")
    void drawsATopic() {
        Menu.Pick pick = pick(fresh(), ABOUT, null);

        assertTrue(pick.ok());
        assertTrue(ABOUT.topics().contains(pick.line().payload().get(Utterance.TOPIC)));
    }
}
