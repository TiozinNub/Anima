package dev.luizloyola.anima.core.brain.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Anima's doings have their words. The compiler makes every instinct name a doing; this makes
 * every remembered one say something.
 */
class DoingLinesTest {

    private static final Map<String, String> EN =
            DoingLines.load(DoingLinesTest.class, "/assets/anima/lang/en_us.json");

    /** Remembered, one slot, and deliberately short of words — the check has to see that. */
    private static final Doing PROBE = Doings.register(
            new Doing("doing_lines_probe", "probe.doing.x", List.of("what"), true));

    @Test
    void everyRememberedDoingOfAnimasHasItsLines() {
        assertEquals(List.of(), DoingLines.problems("anima.", EN));
    }

    @Test
    void everyWhenAndTheFallbackHaveWords() {
        for (When when : When.values()) {
            assertTrue(EN.containsKey(when.langKey()), when.langKey());
        }
        assertTrue(EN.containsKey(Doings.SOMETHING.value()));
    }

    @Test
    void theCheckSeesAMissingLineAndAStrayArgument() {
        assertEquals(3, DoingLines.problems("probe.", Map.of()).size(), "all three missing");

        List<String> problems = DoingLines.problems("probe.", Map.of(
                "probe.doing.x.1", "Did %1$s %2$s.",
                "probe.doing.x.2", "Did %3$s.",
                "probe.doing.x.3", "Did %s."));
        assertEquals(2, problems.size(), "past the slots and when, and not positional: " + problems);
        assertTrue(problems.get(0).startsWith("probe.doing.x.2"), problems.get(0));
        assertTrue(problems.get(1).startsWith("probe.doing.x.3"), problems.get(1));
        assertTrue(PROBE.remembered());
    }
}
