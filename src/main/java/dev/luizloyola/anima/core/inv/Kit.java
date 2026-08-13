package dev.luizloyola.anima.core.inv;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a piece of work calls for, so a body that picks the work knows what to carry before
 * starting — mining needs a pickaxe and wants torches. See {@link ItemCall} for the two strengths
 * and why food is not a kit's business.
 *
 * <p><b>Declared, never stored.</b> A kit is code, derived from the work's type on every ask and
 * checked against the pack rather than checked off: there is no "currently kitting up" state, a
 * suspension resumes by re-asking, and nothing here meets a codec.
 *
 * <p>The board's offer path checks {@link #missingNeeds} before an item is claimed — a body that
 * cannot do the work should not camp it. Nothing reads the wants yet; the wield step covers them
 * at the block.
 */
public final class Kit {

    /** The kit that calls for nothing — the default for work that runs on bare hands. */
    public static final Kit NONE = new Kit(List.of());

    private final List<ItemCall> calls;

    private Kit(List<ItemCall> calls) {
        this.calls = List.copyOf(calls);
    }

    public static Kit of(ItemCall... calls) {
        return calls.length == 0 ? NONE : new Kit(List.of(calls));
    }

    /** Every call, needs and wants alike, in declaration order. */
    public List<ItemCall> calls() {
        return calls;
    }

    public boolean isEmpty() {
        return calls.isEmpty();
    }

    /**
     * The needs {@code pack} does not cover — the gate's whole question. Wants never appear here:
     * their absence is a shrug, not a blocker.
     */
    public List<ItemCall> missingNeeds(Inventory pack) {
        List<ItemCall> missing = new ArrayList<>();
        for (ItemCall call : calls) {
            if (call.strength() == ItemCall.Strength.NEED && !call.coveredBy(pack)) {
                missing.add(call);
            }
        }
        return missing;
    }
}
