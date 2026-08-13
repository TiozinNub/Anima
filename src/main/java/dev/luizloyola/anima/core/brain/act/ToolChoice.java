package dev.luizloyola.anima.core.brain.act;

import java.util.List;

/**
 * Which stack, if any, deserves the hand for the block about to be broken. The {@link MiningSpeed}
 * split, repeated: the mod layer <em>measures</em> (how fast a stack breaks this block, whether it
 * harvests it) and this class only ranks, so the policy is headless-testable.
 *
 * <p>Measured, never tabulated: there is no "axes are for logs" table. Each candidate arrives with
 * its speed against <em>the actual block</em>, so enchanted and modded tools sort with zero curated
 * knowledge, and an axe on dirt measures the same as a fist — durability spared on dirt without
 * ever having heard of dirt.
 *
 * <p>The rules, in the order they decide:
 * <ol>
 *   <li><b>Harvest beats speed</b> when something in the pack is correct: a slow pick that yields
 *       cobble beats a swift axe that yields nothing. With nothing correct, speed alone decides.</li>
 *   <li><b>Strictly faster than the empty hand, or no tool</b> — a tool that ties the fist wears
 *       for nothing.</li>
 *   <li><b>Ties keep the current hand</b>, then the lowest slot, so the same pack always answers
 *       the same way.</li>
 * </ol>
 */
public final class ToolChoice {

    /** Leave the hand exactly as it is — it already holds the winner. */
    public static final int KEEP_HAND = -1;

    /**
     * Empty the hand — nothing in the pack beats bare knuckles on this block, and whatever is
     * held now would take wear for no speed. Idempotent for the caller: a hand already empty
     * stays that way.
     */
    public static final int BARE_HAND = -2;

    /**
     * One measured stack, its {@code speed} against the block in question. Empty slots are not
     * candidates.
     */
    public record Candidate(int slot, float speed, boolean harvests) {
    }

    private ToolChoice() {
    }

    /**
     * Picks the slot to wield for one block, or {@link #KEEP_HAND} / {@link #BARE_HAND}.
     *
     * @param pack         every non-empty storage stack, measured against the target block
     * @param heldSlot     the slot currently in the hand (in the same indexing as the candidates)
     * @param bareSpeed    the empty hand's speed against the same block
     * @param needsHarvest whether the block drops only for a correct tool
     */
    public static int choose(List<Candidate> pack, int heldSlot, float bareSpeed,
                             boolean needsHarvest) {
        boolean harvestMatters = needsHarvest && pack.stream().anyMatch(Candidate::harvests);
        Candidate best = null;
        for (Candidate candidate : pack) {
            if (harvestMatters && !candidate.harvests()) {
                continue;
            }
            best = better(best, candidate, heldSlot);
        }
        if (best == null) {
            return BARE_HAND; // nothing to weigh: bare knuckles are all there is
        }
        if (!harvestMatters && best.speed() <= bareSpeed) {
            return BARE_HAND; // rule 2: a tool that cannot out-dig the fist stays sheathed
        }
        return best.slot() == heldSlot ? KEEP_HAND : best.slot();
    }

    /** The stronger of two candidates: speed first, then held-in-hand, then the lower slot. */
    private static Candidate better(Candidate incumbent, Candidate challenger, int heldSlot) {
        if (incumbent == null) {
            return challenger;
        }
        if (challenger.speed() != incumbent.speed()) {
            return challenger.speed() > incumbent.speed() ? challenger : incumbent;
        }
        if (incumbent.slot() == heldSlot || challenger.slot() == heldSlot) {
            return incumbent.slot() == heldSlot ? incumbent : challenger;
        }
        return challenger.slot() < incumbent.slot() ? challenger : incumbent;
    }
}
