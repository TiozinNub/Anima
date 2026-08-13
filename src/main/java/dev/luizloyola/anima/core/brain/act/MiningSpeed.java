package dev.luizloyola.anima.core.brain.act;

/**
 * The survival player's mining-speed formula, as arithmetic — a line-for-line mirror of
 * {@code Player.getDestroySpeed} (verified against 26.1.2 bytecode; identical on 1.21.11 and
 * 26.2.x). Pure number work, testable without a world: the body reads the effects and attributes
 * off the entity and hands the numbers here.
 *
 * <p>A mirror rather than a call because Haste and Mining Fatigue are hardcoded in that method
 * instead of reaching mining through an attribute — a body that is not a {@code Player} gets none
 * of them, silently and forever.
 *
 * <p>The result is vanilla's "destroy speed", not progress: the caller still divides by the block's
 * hardness and by 30 (correct tool) or 100 (wrong tool), which is
 * {@code BlockState.getDestroyProgress}.
 */
public final class MiningSpeed {

    /** Amplifier value meaning "this effect is not present" — vanilla amplifiers start at 0. */
    public static final int ABSENT = -1;

    public static final double DRY = 1.0;

    private MiningSpeed() {
    }

    /**
     * The speed a body mines at, factors applied in vanilla's order (the order matters once, at
     * the Efficiency gate).
     *
     * @param toolSpeed        the held stack's speed against this block ({@code ItemStack
     *                         .getDestroySpeed}) — 1.0 bare-handed, 8.0 for a diamond axe on wood
     * @param miningEfficiency the {@code mining_efficiency} attribute, which is where the
     *                         Efficiency enchantment lands (level² + 1) since enchantments became
     *                         data-driven. <b>Added only when {@code toolSpeed > 1}</b> — vanilla's
     *                         own gate, and the reason Efficiency on a bare hand does nothing
     * @param hasteAmplifier   Haste or Conduit Power, whichever is stronger, or {@link #ABSENT}
     * @param fatigueAmplifier Mining Fatigue, or {@link #ABSENT}
     * @param blockBreakSpeed  the {@code block_break_speed} attribute — vanilla's general-purpose
     *                         multiplier, the hook a datapack or another mod turns
     * @param submergedFactor  the {@code submerged_mining_speed} attribute when the eyes are in
     *                         water ({@link #DRY} otherwise). The player default is 0.2, i.e. five
     *                         times slower, and Aqua Affinity is a modifier that raises it to 1
     * @param onGround         standing on something; vanilla quarters-and-then-some the speed of
     *                         anyone mining in mid-air
     */
    public static float of(float toolSpeed, double miningEfficiency, int hasteAmplifier,
            int fatigueAmplifier, double blockBreakSpeed, double submergedFactor, boolean onGround) {
        float speed = toolSpeed;
        if (speed > 1.0F) {
            speed += (float) miningEfficiency;
        }
        if (hasteAmplifier > ABSENT) {
            speed *= 1.0F + (hasteAmplifier + 1) * 0.2F;
        }
        if (fatigueAmplifier > ABSENT) {
            speed *= fatigueFactor(fatigueAmplifier);
        }
        speed *= (float) blockBreakSpeed;
        speed *= (float) submergedFactor;
        if (!onGround) {
            speed /= 5.0F;
        }
        return speed;
    }

    /**
     * What one level of Mining Fatigue does. The default case catches every amplifier above 2, so
     * Mining Fatigue IV and 200 are the same near-total refusal. Copied, not derived: vanilla's
     * numbers are not a sequence.
     */
    public static float fatigueFactor(int amplifier) {
        return switch (amplifier) {
            case 0 -> 0.3F;
            case 1 -> 0.09F;
            case 2 -> 0.0027F;
            default -> 8.1E-4F;
        };
    }
}
