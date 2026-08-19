package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.BeingId;

/**
 * The throat — a deliberate call, loud enough to carry past the hearing radius.
 *
 * <p><b>A hail carries no payload</b> (decision: Luiz, social foundations §2). It is a sound and
 * nothing more: the hearer's ear classifies it like a step or a chest lid, and the hearer's own
 * mind decides whether it matters. Nothing is addressed and nothing is delivered, which is what
 * keeps one body from writing into another's head.
 */
public interface Voice {

    /**
     * Call out, so anything within this body's hail radius hears that somebody did.
     *
     * @param whom who this body had IN MIND. Nothing is delivered to them and nothing is addressed
     *     — the sound is a broadcast and anyone in range may answer. The id is here because the
     *     guardrail is per-target: having called somebody is what spends the reason to call them
     *     again, and only the caller knows who it meant.
     */
    void hail(BeingId whom);

    /** A body that cannot call out — every headless rig, and anything mute. */
    Voice NONE = whom -> {
    };
}
