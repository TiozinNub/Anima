package dev.luizloyola.anima.core.agent.need;

import java.util.List;
import java.util.Objects;

/**
 * A named family of {@link Reason}s that <b>prints even when it is empty</b> (decision: Luiz,
 * 2026-08-06, and the whole reason this type exists rather than one flat list).
 *
 * <p>An itemisation that lists only its non-zero terms cannot tell <em>nothing is dragging you
 * down</em> from <em>nobody looked</em>. {@code No debuffs} is a fact about the body; a missing line
 * is a fact about the readout, and a reader has no way to tell them apart.
 *
 * @param key what this family is called — {@link #emptyKey()} hangs off it
 * @param reasons its lines, in the order the source found them; may be empty
 */
public record ReasonGroup(String key, List<Reason> reasons) {

    public ReasonGroup {
        Objects.requireNonNull(key, "key");
        reasons = List.copyOf(reasons);
    }

    /** The one line printed instead when there is nothing in this group. */
    public String emptyKey() {
        return key + ".none";
    }

    public boolean isEmpty() {
        return reasons.isEmpty();
    }
}
