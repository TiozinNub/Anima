package dev.luizloyola.anima.mod.body;

import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The vanilla attributes a body must own before the world can tune it — folded into a consumer's
 * {@code createAttributes()}.
 *
 * <p><b>An undeclared attribute is a silent hole, not a zero.</b> {@code AttributeMap} skips a
 * modifier whose instance is missing without a log line, so an Efficiency V pickaxe is not
 * enchanted and a datapack raising {@code block_break_speed} misses your agents. Reading one is
 * worse: {@code AttributeSupplier.getValue} throws, so {@code AgentBlockBreaker} reads
 * each through a presence check and falls back to the vanilla player's default.
 *
 * <p>Modifiers are data — a status effect, an enchantment, an item component, a datapack, another
 * mod all deliver tuning the same way — so declaring the attribute is the whole of the work.
 *
 * <p><b>Only what something reads.</b> A player declares twelve; these are the four a mining body
 * consults. The combat set (attack damage and speed, where Strength, Weakness and Haste's second
 * half land) waits until an agent can swing at something.
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
}
