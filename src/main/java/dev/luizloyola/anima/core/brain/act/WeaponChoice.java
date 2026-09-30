package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Combatant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Which stack, if any, deserves the hand for a fight: {@link ToolChoice}'s split for blows. The mod
 * layer measures each stack against the target — its hit with the enchantments that count against
 * it, how many hits a second, and how many blows it has left before it breaks — and this class only
 * ranks.
 *
 * <p>The rules, in the order they decide:
 * <ol>
 *   <li><b>The quickest kill wins</b> if it beats the bare fist, counted in whole blows. A weapon
 *       that would break first is worth the blows it has left; the rest of the fight is fought
 *       with the best of the others, after the swap that costs (Luiz, 2026-09-30). A tool's two
 *       wear a blow count here and nowhere else: an axe that hits harder than a sword is drawn
 *       over it.</li>
 *   <li><b>Nothing beats the fist:</b> a held item that hits worse than a fist is put away; one that
 *       hits as well stays, since changing the hand costs a warmup for nothing.</li>
 *   <li><b>Ties keep the current hand</b>, then the lowest slot, so the same pack always answers
 *       the same way.</li>
 * </ol>
 *
 * <p>With no target to measure against, the ranking is plain damage a second and wear is ignored.
 * It answers in {@link ToolChoice}'s terms: a slot, {@link ToolChoice#KEEP_HAND} or
 * {@link ToolChoice#BARE_HAND}.
 */
public final class WeaponChoice {

    /** What {@link Candidate#blowsLeft} says of a stack that never wears. */
    public static final int UNBREAKING = Integer.MAX_VALUE;

    /**
     * One measured stack. Empty slots are not candidates.
     *
     * @param damage    one full-charge hit against the target, before its armour
     * @param perSecond full-charge hits a second
     * @param blowsLeft blows before it breaks, the one that breaks it included; {@link #UNBREAKING}
     */
    public record Candidate(int slot, double damage, double perSecond, int blowsLeft) {

        public double damagePerSecond() {
            return damage * perSecond;
        }
    }

    /** What is being fought, read off its body. */
    public record Foe(double health, double armor, double toughness) {
    }

    private WeaponChoice() {
    }

    /**
     * @param pack        every non-empty storage stack, measured
     * @param heldSlot    the slot currently in the hand, in the candidates' indexing
     * @param bare        the empty hand, whose slot is ignored
     * @param foe         the target, or null when there is none to measure against
     * @param swapSeconds what changing to the next weapon costs when one breaks mid-fight
     */
    public static int choose(List<Candidate> pack, int heldSlot, Candidate bare, @Nullable Foe foe,
                             double swapSeconds) {
        double bareSeconds = seconds(bare, foe);
        Candidate best = null;
        double bestSeconds = Double.POSITIVE_INFINITY;
        Candidate held = null;
        double heldSeconds = Double.POSITIVE_INFINITY;
        for (Candidate candidate : pack) {
            double seconds = seconds(candidate, pack, bare, foe, swapSeconds);
            if (candidate.slot() == heldSlot) {
                held = candidate;
                heldSeconds = seconds;
            }
            if (best == null || seconds < bestSeconds
                    || seconds == bestSeconds && prefer(candidate, best, heldSlot)) {
                best = candidate;
                bestSeconds = seconds;
            }
        }
        if (best != null && bestSeconds < bareSeconds) {
            return best.slot() == heldSlot ? ToolChoice.KEEP_HAND : best.slot();
        }
        return held != null && heldSeconds > bareSeconds ? ToolChoice.BARE_HAND : ToolChoice.KEEP_HAND;
    }

    /**
     * How long {@code weapon} takes to kill {@code foe}, in seconds: its blows, then — if it breaks
     * first — the swap and the quickest finish any other candidate or the fist gives. Without a
     * foe, the inverse of its damage a second: the same order, with nothing to break against.
     */
    private static double seconds(Candidate weapon, List<Candidate> pack, Candidate bare,
                                  @Nullable Foe foe, double swapSeconds) {
        if (foe == null || weapon.blowsLeft() >= blows(weapon, foe, foe.health())) {
            return seconds(weapon, foe);
        }
        double left = foe.health() - weapon.blowsLeft() * hit(weapon, foe);
        double finish = blows(bare, foe, left) / bare.perSecond();
        for (Candidate other : pack) {
            if (other != weapon) {
                finish = Math.min(finish, blows(other, foe, left) / other.perSecond());
            }
        }
        return weapon.blowsLeft() / weapon.perSecond() + swapSeconds + finish;
    }

    /** {@code weapon}'s kill as if it never wore. */
    private static double seconds(Candidate weapon, @Nullable Foe foe) {
        if (foe == null) {
            double perSecond = weapon.damagePerSecond();
            return perSecond > 0.0 ? 1.0 / perSecond : Double.POSITIVE_INFINITY;
        }
        return blows(weapon, foe, foe.health()) / weapon.perSecond();
    }

    /** Whole blows of {@code weapon} it takes to deal {@code health} through the foe's armour. */
    private static double blows(Candidate weapon, Foe foe, double health) {
        double hit = hit(weapon, foe);
        if (hit <= 0.0 || weapon.perSecond() <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, Math.ceil(health / hit - 1e-9));
    }

    private static double hit(Candidate weapon, Foe foe) {
        return Combatant.afterArmor(weapon.damage(), foe.armor(), foe.toughness());
    }

    private static boolean prefer(Candidate challenger, Candidate incumbent, int heldSlot) {
        if (incumbent.slot() == heldSlot || challenger.slot() == heldSlot) {
            return challenger.slot() == heldSlot;
        }
        return challenger.slot() < incumbent.slot();
    }
}
