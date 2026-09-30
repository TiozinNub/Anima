package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.brain.act.ArmorChoice;
import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.inv.ArmorType;
import dev.luizloyola.anima.core.inv.HandChange;
import dev.luizloyola.anima.core.inv.HandChanges;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * Puts on the best armour the pack holds ({@link ArmorChoice}), one piece at a time, each a timed
 * stack move ({@link HandChanges#equip}). A reflex rather than a plan: a player wears what they
 * have. It looks once a second, and only while the hands are free — nothing being broken, eaten or
 * fought, and no other hand change under way — so a fight or a job always takes the hands from it.
 *
 * <p>Owned and ticked by the body, after the brain, so a hand change the brain asked for this tick
 * comes first. Holds nothing of its own: a piece going on is the inventory's change, saved with it.
 */
public final class AgentWardrobe {

    private static final int LOOK_EVERY_TICKS = 20;

    private final AgentBody body;

    public AgentWardrobe(AgentBody body) {
        this.body = body;
    }

    public void tick() {
        Inventory inv = body.inventory();
        long now = body.level().getGameTime();
        HandChange change = inv.change();
        boolean dressing = change != null && change.kind() == HandChange.Kind.EQUIP
                && HandChanges.busy(inv, now);
        if (!dressing && (HandChanges.busy(inv, now) || now % LOOK_EVERY_TICKS != 0)) {
            return;
        }
        if (!handsFree(now)) {
            return; // a piece going on lapses, and is started again once the hands are free
        }
        ArmorChoice.Piece next = choose();
        if (next != null) {
            HandChanges.equip(inv, next.slot(), next.type(), now, body.handTiming());
        }
    }

    private boolean handsFree(long now) {
        return body.blockBreaker().state() != BreakState.BREAKING
                && !body.entity().isUsingItem()
                && !body.striker().inUse(now);
    }

    private ArmorChoice.@Nullable Piece choose() {
        Inventory inv = body.inventory();
        HolderLookup.Provider registries = body.level().registryAccess();
        List<ArmorChoice.Piece> pack = new ArrayList<>();
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            dev.luizloyola.anima.core.inv.ItemStack core = inv.get(slot);
            if (!core.isEmpty()) {
                ArmorChoice.Piece piece = measure(slot, ItemStacks.toVanilla(core, registries));
                if (piece != null) {
                    pack.add(piece);
                }
            }
        }
        if (pack.isEmpty()) {
            return null;
        }
        Map<ArmorType, ArmorChoice.Piece> worn = new EnumMap<>(ArmorType.class);
        for (ArmorType type : ArmorType.values()) {
            dev.luizloyola.anima.core.inv.ItemStack core = inv.armor(type);
            ArmorChoice.Piece piece = core.isEmpty() ? null
                    : measure(-1, ItemStacks.toVanilla(core, registries));
            if (piece != null) {
                worn.put(type, piece);
            } else if (!core.isEmpty()) {
                // Something no armour slot is made for, put there by hand: it weighs nothing.
                worn.put(type, new ArmorChoice.Piece(-1, type, 0.0, 0.0, false));
            }
        }
        return ArmorChoice.choose(pack, worn);
    }

    /** {@code stack} as a piece of armour, or null when it goes on no armour slot. */
    private static ArmorChoice.@Nullable Piece measure(int slot, ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        ArmorType type = equippable == null ? null : typeOf(equippable.slot());
        if (type == null) {
            return null;
        }
        double[] armor = {0.0};
        double[] toughness = {0.0};
        stack.forEachModifier(equippable.slot(), (attribute, modifier) -> {
            if (modifier.operation() != net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                return;
            }
            if (attribute.value() == Attributes.ARMOR.value()) {
                armor[0] += modifier.amount();
            } else if (attribute.value() == Attributes.ARMOR_TOUGHNESS.value()) {
                toughness[0] += modifier.amount();
            }
        });
        boolean bound = EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
        return new ArmorChoice.Piece(slot, type, armor[0], toughness[0], bound);
    }

    private static @Nullable ArmorType typeOf(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> ArmorType.HEAD;
            case CHEST -> ArmorType.CHEST;
            case LEGS -> ArmorType.LEGS;
            case FEET -> ArmorType.FEET;
            default -> null;
        };
    }
}
