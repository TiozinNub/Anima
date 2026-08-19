package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.BeingId;

/**
 * The throat — a deliberate call, loud enough to carry past the hearing radius, and the tally of
 * who this body has reached out to.
 *
 * <p><b>A hail carries no payload</b> (decision: Luiz, social foundations §2). It is a sound and
 * nothing more: the hearer's ear classifies it like a step or a chest lid, and the hearer's own
 * mind decides whether it matters. Nothing is addressed and nothing is delivered, which is what
 * keeps one body from writing into another's head.
 *
 * <p><b>The mark and the sound are one port because they are one intent.</b> Reaching out to
 * somebody is what spends the reason to reach out to them again, whether it ended in a shout or
 * in a walk — and only the reaching body knows who it meant. Split across two ports, a shout and
 * a walk would spend different marks and a body could re-target the same neighbour forever.
 */
public interface Voice {

    /**
     * Call out, so anything within this body's hail radius hears that somebody did — and mark
     * {@code whom} as reached out to, exactly as {@link #reachedOut} does.
     *
     * @param whom who this body had IN MIND. Nothing is delivered to them and nothing is addressed
     *     — the sound is a broadcast and anyone in range may answer. The id is here because the
     *     guardrail is per-target: having called somebody is what spends the reason to call them
     *     again, and only the caller knows who it meant.
     */
    void hail(BeingId whom);

    /**
     * Mark {@code whom} as reached out to without making a sound — what walking over is when the
     * body is already within earshot and a shout would add nothing.
     *
     * <p>Silent, not idle: the mark is the whole of the guardrail's "and I have not tried lately",
     * so a target picked and walked to is spent for the same patience a shouted-at one is.
     */
    void reachedOut(BeingId whom);

    /** A body that cannot call out — every headless rig, and anything mute. */
    Voice NONE = new Voice() {
        @Override
        public void hail(BeingId whom) {
        }

        @Override
        public void reachedOut(BeingId whom) {
        }
    };
}
