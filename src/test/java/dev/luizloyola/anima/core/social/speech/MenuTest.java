package dev.luizloyola.anima.core.social.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The menu is the picker read by a participant who cannot want: offered is applicable-on-the-beat,
 * and a pick is refused for exactly the reason a player can act on.
 */
class MenuTest {

    private static final int CAP = 60;

    private final AgentId player = AgentId.random();
    private final AgentId body = AgentId.random();
    private final Random random = new Random(7);

    /** A topic-bearing test act, registered once per JVM (the registry is shared across suites). */
    private static final SpeechAct ABOUT = registerAbout();

    private static SpeechAct registerAbout() {
        SpeechAct act = new SpeechAct("about_test", "anima.speech.test.about", 1, true, false,
                false, false, List.of(), List.of("rain", "roads"));
        try {
            return SpeechActs.register(act);
        } catch (IllegalStateException alreadyRegistered) {
            return SpeechActs.byKey("about_test").orElseThrow();
        }
    }

    private Encounter fresh() {
        return new Encounter(UUID.randomUUID(), List.of(player, body), 0L);
    }

    private static Utterance line(AgentId who, SpeechAct act, long tick) {
        return new Utterance(who, act.key(), Map.of(), tick);
    }

    @Test
    @DisplayName("an empty record offers the picker's applicable set at once")
    void emptyRecordOffersAtOnce() {
        List<SpeechAct> offered = Menu.offered(fresh(), player, 0L, CAP);

        assertTrue(offered.contains(SpeechActs.GREETING));
        assertFalse(offered.contains(SpeechActs.HAIL), "the hail opens a record; it is not said");
        assertFalse(offered.contains(SpeechActs.IGNORED), "a system verdict is never on a menu");
    }

    @Test
    @DisplayName("nothing is offered inside the beat after the last line, everything after it")
    void beatGatesTheOffer() {
        Encounter e = fresh();
        e.append(line(body, SpeechActs.GREETING, 100L));

        assertTrue(Menu.offered(e, player, 100L + Picker.REPLY_GRACE_TICKS - 1, CAP).isEmpty());
        assertFalse(Menu.offered(e, player, 100L + Picker.REPLY_GRACE_TICKS, CAP).isEmpty());
    }

    @Test
    @DisplayName("a pick of an act the registry does not hold is UNKNOWN")
    void unknownAct() {
        Menu.Pick pick = Menu.pick(fresh(), player, 0L, CAP, "no_such_word", null, random);

        assertEquals(Menu.Reason.UNKNOWN, pick.reason());
        assertNull(pick.line());
    }

    @Test
    @DisplayName("a registered act the picker would not offer is NOT_OFFERED, even before the beat")
    void notOfferedOutranksTooSoon() {
        Encounter e = fresh();
        e.append(line(body, SpeechActs.GREETING, 100L));

        // END_CHAT answers a request_end_chat only — off the table whatever the tick, and the
        // beat is unspent too: the refusal must name the reason the player can do something about.
        Menu.Pick pick = Menu.pick(e, player, 101L, CAP, SpeechActs.END_CHAT.key(), null, random);

        assertEquals(Menu.Reason.NOT_OFFERED, pick.reason());
    }

    @Test
    @DisplayName("an offered act inside the beat is TOO_SOON")
    void tooSoon() {
        Encounter e = fresh();
        e.append(line(body, SpeechActs.GREETING, 100L));

        Menu.Pick pick = Menu.pick(e, player, 101L, CAP, SpeechActs.GREETING.key(), null, random);

        assertEquals(Menu.Reason.TOO_SOON, pick.reason());
    }

    @Test
    @DisplayName("an offered act on the beat is OK with an empty payload")
    void okPlain() {
        Menu.Pick pick = Menu.pick(fresh(), player, 0L, CAP, SpeechActs.GREETING.key(), null, random);

        assertTrue(pick.ok());
        assertEquals(SpeechActs.GREETING, pick.line().act());
        assertTrue(pick.line().payload().isEmpty());
    }

    @Test
    @DisplayName("a topic on an act that carries none is BAD_TOPIC")
    void topicOnTopiclessAct() {
        Menu.Pick pick = Menu.pick(fresh(), player, 0L, CAP, SpeechActs.GREETING.key(), "rain", random);

        assertEquals(Menu.Reason.BAD_TOPIC, pick.reason());
    }

    @Test
    @DisplayName("a topic-bearing act takes a declared topic and refuses an undeclared one")
    void declaredTopicsOnly() {
        Menu.Pick declared = Menu.pick(fresh(), player, 0L, CAP, ABOUT.key(), "roads", random);
        Menu.Pick undeclared = Menu.pick(fresh(), player, 0L, CAP, ABOUT.key(), "weather", random);

        assertTrue(declared.ok());
        assertEquals("roads", declared.line().payload().get(Utterance.TOPIC));
        assertEquals(Menu.Reason.BAD_TOPIC, undeclared.reason());
    }

    @Test
    @DisplayName("a topic-bearing act with no topic given draws one of its own")
    void drawsATopic() {
        Menu.Pick pick = Menu.pick(fresh(), player, 0L, CAP, ABOUT.key(), null, random);

        assertTrue(pick.ok());
        assertTrue(ABOUT.topics().contains(pick.line().payload().get(Utterance.TOPIC)));
    }
}
