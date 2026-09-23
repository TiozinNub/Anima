package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doing;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.history.When;
import dev.luizloyola.anima.core.social.speech.Recounting;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Speeches}'s two pure seams. The rest of {@code deliver} is per-recipient composition over a
 * live {@code MinecraftServer} and a room of loaded bodies — nothing this suite can stand up — but
 * the two decisions that would silently render the WRONG STRING are arithmetic and string building,
 * and those are checkable here.
 *
 * <p>The acts are built rather than taken from {@code SpeechActs}: the registry's own vocabulary is
 * its test's business, and pinning a variant count here would make this fail the day somebody writes
 * a third way to say hello.
 */
class SpeechesTest {

    private static SpeechAct act(String langKey, int variants) {
        return new SpeechAct(langKey, langKey, variants, true, false, false, false, List.of());
    }

    /**
     * Fixed rather than random, because {@code Objects.hash} multiplies by 31 and 31 ≡ 1 (mod 3):
     * for a three-variant act the pick reduces to {@code id.hashCode() + lineIndex} mod 3, so two
     * random ids agree on <em>every</em> line one time in three. A "they differ somewhere" loop over
     * random ids would flake exactly that often.
     */
    private static final UUID ONE = new UUID(0L, 1L);
    private static final UUID TWO = new UUID(0L, 2L);

    @Test
    @DisplayName("a variant is 1-based and inside the act's range")
    void variantsStayInRange() {
        for (int variants = 1; variants <= 5; variants++) {
            for (int line = 0; line < 64; line++) {
                int picked = Speeches.variantOf(ONE, line, variants);
                assertTrue(picked >= 1 && picked <= variants,
                        "variant " + picked + " is outside 1.." + variants);
            }
        }
    }

    @Test
    @DisplayName("a variant is derived from the record and the line, never drawn")
    void variantsAreStable() {
        // Pinned to a value, not merely to itself: every recipient composes their own copy of one
        // line, and a restored conversation re-derives what it already said, so anything drawn from
        // a random source is a bug this exact assertion is here to catch.
        assertEquals(3, Speeches.variantOf(ONE, 0, 3));
    }

    @Test
    @DisplayName("a different record says it differently")
    void variantsDifferBetweenRecords() {
        assertEquals(1, Speeches.variantOf(TWO, 0, 3), "the encounter id is not in the hash");
    }

    @Test
    @DisplayName("a later line of one record says it differently")
    void variantsDifferBetweenLines() {
        assertEquals(1, Speeches.variantOf(ONE, 1, 3), "the line index is not in the hash");
    }

    @Test
    @DisplayName("a multi-variant act renders through a numbered key")
    void keyCarriesTheVariant() {
        assertEquals("anima.speech.greeting.2",
                Speeches.renderKey(act("anima.speech.greeting", 3), Map.of(), 2));
    }

    @Test
    @DisplayName("a single-variant act drops the suffix")
    void singleVariantActsHaveNoNumber() {
        assertEquals("anima.speech.deflect",
                Speeches.renderKey(act("anima.speech.deflect", 1), Map.of(), 1));
    }

    @Test
    @DisplayName("a topic in the payload picks a sub-vocabulary")
    void topicSplitsTheKey() {
        assertEquals("autarkia.speech.remark.weather.2",
                Speeches.renderKey(act("autarkia.speech.remark", 3), Map.of("topic", "weather"), 2));
    }

    @Test
    @DisplayName("a topic on a single-variant act still drops the suffix")
    void topicAndSingleVariantCombine() {
        assertEquals("autarkia.speech.remark.weather",
                Speeches.renderKey(act("autarkia.speech.remark", 1), Map.of("topic", "weather"), 1));
    }

    @Test
    @DisplayName("a payload without a topic is the plain key")
    void otherPayloadEntriesAreNotTopics() {
        assertEquals("anima.speech.greeting.1",
                Speeches.renderKey(act("anima.speech.greeting", 3), Map.of("mood", "warm"), 1));
    }

    /**
     * A told deed renders from the doing's own key, slots in declared order and {@code when} last —
     * the order a line's {@code %1$s…} was written against. Lang slots only: an item or a species
     * needs the game's registries, which this suite does not stand up.
     */
    @Test
    void aToldDeedTakesTheDoingsKeyAndItsSlotsInOrderThenWhen() {
        Doing gathering = Doings.byKey("speeches_test_gathering").orElseGet(() -> Doings.register(
                new Doing("speeches_test_gathering", "test.doing.gathering", List.of("item", "for"),
                        true)));
        Deed deed = Deed.of(gathering, Slot.lang("test.goods.logs"), Slot.lang("test.purpose.yard"));

        Component line = Speeches.told(new Recounting.Told(deed, When.YESTERDAY), 2);

        TranslatableContents said = (TranslatableContents) line.getContents();
        assertEquals("test.doing.gathering.2", said.getKey());
        List<String> args = java.util.Arrays.stream(said.getArgs())
                .map(arg -> ((TranslatableContents) ((Component) arg).getContents()).getKey())
                .toList();
        assertEquals(List.of("test.goods.logs", "test.purpose.yard", "anima.when.yesterday"), args);
    }
}
