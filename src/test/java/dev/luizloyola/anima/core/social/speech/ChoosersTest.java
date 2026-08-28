package dev.luizloyola.anima.core.social.speech;

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
}
