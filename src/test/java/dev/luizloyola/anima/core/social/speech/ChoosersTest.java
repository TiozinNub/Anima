package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one consumer slot: {@link Choosers#BASIC} until a consumer calls {@link Choosers#provide},
 * and whoever registered last after that. The slot is static — shared with every other test in
 * the JVM — so every test here restores BASIC before it hands control back.
 */
class ChoosersTest {

    @AfterEach
    void restoreBasic() {
        Choosers.provide(Choosers.BASIC);
    }

    @Test
    @DisplayName("get answers BASIC before any consumer has provided a chooser")
    void getIsBasicByDefault() {
        assertSame(Choosers.BASIC, Choosers.get());
    }

    @Test
    @DisplayName("provide swaps the slot, and a second provide replaces the first — last one wins")
    void provideSwapsTheSlotAndLastRegistrationWins() {
        Chooser first = (ctx, turn) -> null;
        Chooser second = (ctx, turn) -> null;

        Choosers.provide(first);
        assertSame(first, Choosers.get(), "the consumer's chooser is now what get() answers");

        Choosers.provide(second);
        assertSame(second, Choosers.get(), "a further provide replaces the prior registration");
        assertNotSame(first, Choosers.get());
    }

    @Test
    @DisplayName("after a test provides its own chooser, teardown leaves BASIC for the next test")
    void teardownRestoresBasicForTheNextTest() {
        Choosers.provide((ctx, turn) -> null);
        assertNotSame(Choosers.BASIC, Choosers.get(), "sanity: the slot really did change");

        restoreBasic();

        assertSame(Choosers.BASIC, Choosers.get());
    }

    @Test
    @DisplayName("BASIC holds its tongue while awaiting an answer, even when it would otherwise propose")
    void basicReturnsNullWhileAwaitingEvenWhenItWouldOtherwisePropose() {
        AgentId alice = AgentId.random();
        AgentId bob = AgentId.random();
        Encounter e = new Encounter(UUID.randomUUID(), List.of(alice, bob), 0L);
        Utterance selfAsk = new Utterance(alice, SpeechActs.REQUEST_END_CHAT.key(), Map.of(), 0L);
        // greeted, and REQUEST_END_CHAT applicable, with company pressure at the fresh default of
        // 0.0 — every condition BASIC's own initiating branch wants, but for the gate this pins.
        Chooser.Turn turn = new Chooser.Turn(e, List.of(SpeechActs.REQUEST_END_CHAT),
                Optional.empty(), Optional.of(selfAsk), true, Optional.of(bob));

        Chooser.Line line = Choosers.BASIC.choose(new FakeContext(), turn);

        assertNull(line, "alice already asked bob to end chat — asking again talks over her own question");
    }
}
