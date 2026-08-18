package dev.luizloyola.anima.mod.webdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retained model: what the browser is assumed to be holding, and the difference between that
 * and what the tick just built.
 *
 * <p>Values here are rendered JSON fragments, which is what the real thing carries — a section
 * arrives from Gson already flattened to one canonical string, and comparing strings is the
 * cheapest thing that can be spent inside a tick.
 */
class WebModelTest {

    /** Insertion-ordered, and nullable by design: a null value is a key being dropped. */
    private static Map<String, String> fresh(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    @DisplayName("everything is new against an empty model")
    void firstBuildIsWhole() {
        WebModel.Update update = WebModel.EMPTY.against(7, fresh("health", "{\"tps\":20}"));

        assertEquals(fresh("health", "{\"tps\":20}"), update.delta());
        assertEquals("{\"tick\":7,\"health\":{\"tps\":20}}", update.model().full());
    }

    @Test
    @DisplayName("a section that came out identical is dropped rather than sent")
    void unchangedIsDropped() {
        WebModel one = WebModel.EMPTY.against(1, fresh("health", "{\"tps\":20}")).model();

        WebModel.Update update = one.against(2, fresh("health", "{\"tps\":20}"));

        assertTrue(update.delta().isEmpty(), "an identical fragment is not news");
    }

    @Test
    @DisplayName("a changed section is sent, and becomes what the browser is assumed to hold")
    void changedIsSentAndRetained() {
        WebModel one = WebModel.EMPTY.against(1, fresh("health", "{\"tps\":20}")).model();

        WebModel.Update update = one.against(2, fresh("health", "{\"tps\":12}"));

        assertEquals(fresh("health", "{\"tps\":12}"), update.delta());
        assertEquals("{\"tick\":2,\"health\":{\"tps\":12}}", update.model().full());
    }

    @Test
    @DisplayName("a key not built this tick keeps its retained value and is not resent")
    void absentKeyIsUntouched() {
        WebModel one = WebModel.EMPTY
                .against(1, fresh("health", "{\"tps\":20}", "agents", "[]")).model();

        WebModel.Update update = one.against(2, fresh("health", "{\"tps\":12}"));

        assertEquals(fresh("health", "{\"tps\":12}"), update.delta());
        assertEquals("{\"tick\":2,\"health\":{\"tps\":12},\"agents\":[]}", update.model().full());
    }

    @Test
    @DisplayName("a null value drops the key from the model and tells the browser to drop it")
    void nullRemoves() {
        Map<String, String> opened = fresh("samples", "[1,2]");
        WebModel one = WebModel.EMPTY.against(1, opened).model();

        Map<String, String> closed = new LinkedHashMap<>();
        closed.put("samples", null);
        WebModel.Update update = one.against(2, closed);

        assertTrue(update.delta().containsKey("samples"));
        assertEquals(null, update.delta().get("samples"));
        assertEquals("{\"tick\":2}", update.model().full());
    }

    @Test
    @DisplayName("dropping a key that was never there is not news")
    void nullOnAnAbsentKeyIsQuiet() {
        Map<String, String> closed = new LinkedHashMap<>();
        closed.put("samples", null);

        assertTrue(WebModel.EMPTY.against(1, closed).delta().isEmpty());
    }

    @Test
    @DisplayName("a frame writes the tick, then the keys, and collects drops in their own array")
    void frameAssembly() {
        Map<String, String> delta = new LinkedHashMap<>();
        delta.put("health", "{\"tps\":20}");
        delta.put("samples", null);

        assertEquals("{\"tick\":9,\"health\":{\"tps\":20},\"drop\":[\"samples\"]}",
                WebModel.frame(9, delta));
    }

    @Test
    @DisplayName("a JSON-null value and a dropped key share the same frame, in different places — "
            + "JSON has only one null, so only the drop array tells a removal from a value")
    void jsonNullAndDropCoexist() {
        Map<String, String> frame = new LinkedHashMap<>();
        frame.put("actingAs", "null");  // JSON-null fragment: an ordinary value
        frame.put("samples", null);      // Java null: a key to drop

        assertEquals("{\"tick\":7,\"actingAs\":null,\"drop\":[\"samples\"]}",
                WebModel.frame(7, frame));
    }

    @Test
    @DisplayName("a heartbeat is a frame with nothing in it but the tick")
    void heartbeatFrame() {
        assertEquals("{\"tick\":9}", WebModel.frame(9, Map.of()));
    }

    @Test
    @DisplayName("an empty model has nothing to say hello with")
    void emptyIsEmpty() {
        assertTrue(WebModel.EMPTY.isEmpty());
        assertFalse(WebModel.EMPTY.against(1, fresh("agents", "[]")).model().isEmpty());
    }

    @Test
    @DisplayName("a JSON-null fragment is retained and diffed normally, not confused with key removal — "
            + "the two kinds of null are one reference check apart")
    void jsonNullIsNotKeyRemoval() {
        // First tick: send the JSON-null fragment as actingAs
        WebModel.Update first = WebModel.EMPTY.against(1, fresh("actingAs", "null"));

        // Must appear in delta: it is new
        assertEquals(fresh("actingAs", "null"), first.delta());
        // Must be retained and rendered as JSON null
        assertEquals("{\"tick\":1,\"actingAs\":null}", first.model().full());

        // Second tick: same JSON-null fragment again
        WebModel.Update second = first.model().against(2, fresh("actingAs", "null"));

        // Must NOT appear in delta: identical fragment is not news
        assertTrue(second.delta().isEmpty(), "identical fragment is not news");
        // Must still be retained for a reconnecting browser
        assertEquals("{\"tick\":2,\"actingAs\":null}", second.model().full());
    }
}
