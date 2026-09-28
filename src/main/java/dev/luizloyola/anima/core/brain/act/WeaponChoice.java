package dev.luizloyola.anima.core.brain.act;

import java.util.List;

/**
 * Which stack, if any, deserves the hand for a fight: {@link ToolChoice}'s split for blows. The mod
 * layer measures each stack's damage per second — its attack damage times its attack speed, the two
 * numbers a player reads on the tooltip — and this class only ranks.
 *
 * <p>The rules, in the order they decide:
 * <ol>
 *   <li><b>The most damage per second wins</b> if it beats the bare fist.</li>
 *   <li><b>Nothing beats the fist:</b> a held item that hits worse than a fist is put away; one that
 *       hits as well stays, since changing the hand costs a warmup for nothing.</li>
 *   <li><b>Ties keep the current hand</b>, then the lowest slot, so the same pack always answers
 *       the same way.</li>
 * </ol>
 *
 * <p>It answers in {@link ToolChoice}'s terms: a slot, {@link ToolChoice#KEEP_HAND} or
 * {@link ToolChoice#BARE_HAND}.
 */
public final class WeaponChoice {

    /** One measured stack. Empty slots are not candidates. */
    public record Candidate(int slot, double damagePerSecond) {
    }

    private WeaponChoice() {
    }

    /**
     * @param pack     every non-empty storage stack, measured
     * @param heldSlot the slot currently in the hand, in the candidates' indexing
     * @param bareDamagePerSecond the empty hand's
     */
    public static int choose(List<Candidate> pack, int heldSlot, double bareDamagePerSecond) {
        Candidate best = null;
        Candidate held = null;
        for (Candidate candidate : pack) {
            if (candidate.slot() == heldSlot) {
                held = candidate;
            }
            best = better(best, candidate, heldSlot);
        }
        if (best != null && best.damagePerSecond() > bareDamagePerSecond) {
            return best.slot() == heldSlot ? ToolChoice.KEEP_HAND : best.slot();
        }
        return held != null && held.damagePerSecond() < bareDamagePerSecond
                ? ToolChoice.BARE_HAND : ToolChoice.KEEP_HAND;
    }

    private static Candidate better(Candidate incumbent, Candidate challenger, int heldSlot) {
        if (incumbent == null) {
            return challenger;
        }
        if (challenger.damagePerSecond() != incumbent.damagePerSecond()) {
            return challenger.damagePerSecond() > incumbent.damagePerSecond() ? challenger : incumbent;
        }
        if (incumbent.slot() == heldSlot || challenger.slot() == heldSlot) {
            return incumbent.slot() == heldSlot ? incumbent : challenger;
        }
        return challenger.slot() < incumbent.slot() ? challenger : incumbent;
    }
}
