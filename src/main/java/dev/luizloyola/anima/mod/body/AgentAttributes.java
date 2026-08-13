package dev.luizloyola.anima.mod.body;

import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The vanilla attributes a body must own before the world can tune it, folded into a consumer's
 * {@code createAttributes()}.
 *
 * <p>An undeclared attribute is a silent hole, not a zero: {@code AttributeMap} drops a modifier
 * whose instance is missing, without a log line, so an Efficiency V pickaxe is not enchanted and a
 * datapack's {@code block_break_speed} misses your agents. Reading one throws
 * {@code IllegalArgumentException} out of {@code AttributeSupplier.getValue}, so
 * {@code AgentBlockBreaker} reads each through a presence check with a player default.
 *
 * <p>Modifiers are data (effects, enchantments, item components, datapacks, other mods), so
 * declaring the attribute is the whole of the work, and content nobody has written yet lands too.
 * That is why {@link #combat} is declared though nothing reads it.
 */
public final class AgentAttributes {

    private AgentAttributes() {
    }

    /**
     * Adds the mining and block-reach attributes at their vanilla defaults — a player's own values.
     *
     * <ul>
     *   <li>{@code mining_efficiency} (0) — where the Efficiency enchantment lands
     *   <li>{@code block_break_speed} (1) — the general multiplier a datapack or another mod turns
     *   <li>{@code submerged_mining_speed} (0.2) — the underwater penalty, and where Aqua Affinity
     *       lands
     *   <li>{@code block_interaction_range} (4.5) — arm's reach, so a reach modifier reaches
     * </ul>
     *
     * <p>Call it on the builder a body already has, in its entity type's attribute registration:
     * {@snippet :
     * return AgentAttributes.mining(LivingEntity.createLivingAttributes()
     *         .add(Attributes.MOVEMENT_SPEED, 0.1));
     * }
     */
    public static AttributeSupplier.Builder mining(AttributeSupplier.Builder builder) {
        return builder
                .add(Attributes.MINING_EFFICIENCY)
                .add(Attributes.BLOCK_BREAK_SPEED)
                .add(Attributes.SUBMERGED_MINING_SPEED)
                .add(Attributes.BLOCK_INTERACTION_RANGE);
    }

    /**
     * Adds the player's combat attributes, at the player's own values.
     *
     * <ul>
     *   <li>{@code attack_damage} (1, a fist — <b>not</b> the attribute's own default of 2) —
     *       where a weapon's damage, Strength (+3/level) and Weakness (-4/level) all land
     *   <li>{@code attack_speed} (4) — where a weapon's swing rate lands, and the half of Haste
     *       and Mining Fatigue that is not about mining
     *   <li>{@code sweeping_damage_ratio} (0) — where Sweeping Edge lands
     * </ul>
     *
     * <p>Nothing reads these yet; they are declared because the alternative is a hole, not a
     * default (see the class note). Vanilla reads {@code attack_damage} only off a {@code Mob} (its
     * own) or a {@code Player}, so declaring them changes no behaviour today.
     */
    public static AttributeSupplier.Builder combat(AttributeSupplier.Builder builder) {
        return builder
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.ATTACK_SPEED)
                .add(Attributes.SWEEPING_DAMAGE_RATIO);
    }
}
