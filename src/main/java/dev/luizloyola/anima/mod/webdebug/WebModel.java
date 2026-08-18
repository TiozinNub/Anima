package dev.luizloyola.anima.mod.webdebug;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the browser is assumed to be holding: the last rendered fragment published for each
 * top-level key of the frame, and the tick it was current at.
 *
 * <p><b>This is what makes a partial frame safe.</b> A reader that connects — first time or after a
 * drop — is written {@link #full}, so it merges its deltas onto a whole world rather than onto
 * holes. SSE is ordered and lossless until the socket dies, and when it dies the reconnect starts
 * from a full picture; that is why there is no sequence number, no patch log and no resync route
 * anywhere in this design.
 *
 * <p>Immutable and swapped whole, for the reason everything else here is.
 *
 * <p><b>Two kinds of null, and they are not the same one.</b> A Java {@code null} value in a fresh
 * build means <em>remove this key</em> — it leaves the model and the browser is told to drop it. A
 * JSON null is the four-character fragment {@code "null"}, which is an ordinary value: it is what
 * {@code actingAs} carries when nobody is being acted as.
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

    /** The whole retained world as one frame — what a connecting reader is written. */
    String full() {
        return frame(tick, keys);
    }

    /**
     * One frame: the tick, then the keys given.
     *
     * <p>Assembled by hand rather than through Gson because every value is <em>already</em> a
     * rendered fragment. Re-parsing them into a tree to serialise it again would be the one
     * avoidable cost in a method that runs inside the tick.
     */
    static String frame(int tick, Map<String, String> keys) {
        StringBuilder out = new StringBuilder(64).append("{\"tick\":").append(tick);
        for (Map.Entry<String, String> key : keys.entrySet()) {
            out.append(",\"").append(key.getKey()).append("\":")
                    .append(key.getValue() == null ? "null" : key.getValue());
        }
        return out.append('}').toString();
    }

    /** A model and the delta that took it there, read together so the two cannot disagree. */
    record Update(WebModel model, Map<String, String> delta) {
    }
}
