package dev.luizloyola.anima.mod.webdebug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the browser is assumed to be holding: the last rendered fragment published for each
 * top-level key of the frame, and the tick it was current at.
 *
 * <p><b>This is what makes a partial frame safe.</b> A reader that connects — first time or after a
 * drop — is written {@link #full}, so it merges its deltas onto a whole world rather than onto
 * holes. SSE is ordered and lossless until the socket dies, and when it dies the reconnect starts
 * from a full picture; that is why there is no sequence number and no patch log. The resync is the
 * same greeting sent again: a reader the hand-off outran is written {@link #full} mid-connection
 * rather than a delta whose predecessor it never saw — see {@code WebDebugger.pump}.
 *
 * <p>Immutable and swapped whole, for the reason everything else here is.
 *
 * <p><b>Two kinds of null, and they are not the same one.</b> A Java {@code null} value in a fresh
 * build means <em>remove this key</em> — it leaves the model and the browser is told to drop it. A
 * JSON null is the four-character fragment {@code "null"}, which is an ordinary value: it is what
 * {@code actingAs} carries when nobody is being acted as. Because JSON cannot distinguish these two,
 * the wire format does not encode removals as null values; instead, {@link #frame} collects them
 * in a {@code "drop"} array, leaving null free for its ordinary meaning.
 */
record WebModel(int tick, Map<String, String> keys) {

    /** A server that has published nothing. */
    static final WebModel EMPTY = new WebModel(0, Map.of());

    WebModel {
        keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    /** Whether anything has ever been retained — a reader arriving now has nothing to be told. */
    boolean isEmpty() {
        return keys.isEmpty();
    }

    /**
     * This model updated by a fresh build, and the delta that difference is worth sending.
     *
     * <p>Only the keys the fresh build carries are considered: a key its clock did not fire for is
     * neither compared nor resent, which is the whole point of the clocks.
     */
    Update against(int tick, Map<String, String> fresh) {
        Map<String, String> next = new LinkedHashMap<>(keys);
        Map<String, String> delta = new LinkedHashMap<>();
        for (Map.Entry<String, String> built : fresh.entrySet()) {
            String key = built.getKey();
            String value = built.getValue();
            if (value == null) {
                if (next.remove(key) != null) {
                    delta.put(key, null);
                }
            } else if (!value.equals(next.put(key, value))) {
                delta.put(key, value);
            }
        }
        return new Update(new WebModel(tick, next), delta);
    }

    /**
     * The whole retained world as one frame — what a connecting reader is written.
     *
     * <p><b>{@code "full":true} is what tells a consumer to REPLACE what it holds rather than merge
     * onto it, and this is the only frame on the wire that means that.</b> Merging a greeting would
     * make a re-greeting pointless: a key the greeting omits because it has since been dropped
     * would survive the very frame sent to clear it. {@link #frame} never carries the flag — a
     * delta that claimed replacement semantics would blank every section it did not happen to
     * rebuild.
     */
    String full() {
        StringBuilder out = new StringBuilder(64)
                .append("{\"tick\":").append(tick).append(",\"full\":true");
        return body(out, keys).append('}').toString();
    }

    /** One delta frame: the tick, then the keys given. Never {@code full}. @see #body */
    static String frame(int tick, Map<String, String> keys) {
        StringBuilder out = new StringBuilder(64).append("{\"tick\":").append(tick);
        return body(out, keys).append('}').toString();
    }

    /**
     * The keys of a frame, appended to an opening already written.
     *
     * <p>Assembled by hand rather than through Gson because every value is <em>already</em> a
     * rendered fragment. Re-parsing them into a tree to serialise it again would be the one
     * avoidable cost in a method that runs inside the tick.
     */
    private static StringBuilder body(StringBuilder out, Map<String, String> keys) {
        // Null until the first removal: most frames drop nothing, and this runs inside the tick.
        List<String> dropped = null;
        for (Map.Entry<String, String> key : keys.entrySet()) {
            if (key.getValue() == null) {
                if (dropped == null) {
                    dropped = new ArrayList<>();
                }
                dropped.add(key.getKey());
            } else {
                out.append(",\"").append(key.getKey()).append("\":")
                        .append(key.getValue());
            }
        }
        if (dropped != null) {
            out.append(",\"drop\":[");
            for (int i = 0; i < dropped.size(); i++) {
                if (i > 0) out.append(",");
                out.append("\"").append(dropped.get(i)).append("\"");
            }
            out.append("]");
        }
        return out;
    }

    /** A model and the delta that took it there, read together so the two cannot disagree. */
    record Update(WebModel model, Map<String, String> delta) {
    }
}
