package dev.luizloyola.anima.compat.agent;

import dev.luizloyola.anima.mixin.MeleeAttackGoalInvoker;
import dev.luizloyola.anima.mixin.MobGoalSelectorAccessor;
import dev.luizloyola.anima.mixin.RangedAttackGoalAccessor;
import dev.luizloyola.anima.mixin.RangedBowAttackGoalAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * How a mob hurts a body that is not a player: what one blow deals, how many ticks apart, whether
 * armour stops it, and what it leaves behind. Numbers and citations are in the mob attack
 * reference (2026-09-30).
 *
 * <p>Read live wherever a goal holds the number, so the world's difficulty and a modded mob using
 * vanilla's goals both answer for themselves: the melee goal's interval, a bow goal's pause (which
 * a skeleton sets from the difficulty), a ranged goal's bounds, a crossbow's charge time. What
 * vanilla writes into a goal's or a brain behaviour's code instead — a blaze's volley, a ghast's
 * charge, a shulker's bullets, a hoglin's cooldown — cannot be read, and is the table below.
 *
 * <p>Difficulty never scales the damage itself against a non-player ({@code Player.hurtServer}
 * alone does), but it moves arrow damage, bow cadence and the poison a bite leaves.
 */
public final class MobAttacks {

    /**
     * One mob's attack.
     *
     * @param lingerTicks one point of armour-piercing damage every this many ticks while it keeps
     *                    hitting — poison, wither, burning; 0 for none
     */
    public record Attack(double damage, double intervalTicks, boolean piercing, int lingerTicks) {

        static final Attack NONE = new Attack(0.0, 20.0, false, 0);

        public double perSecond() {
            return damage > 0.0 ? 20.0 / intervalTicks : 0.0;
        }

        Attack leaving(int ticks) {
            return new Attack(damage, intervalTicks, piercing, ticks);
        }
    }

    /** A bow is drawn for 20 ticks before each shot ({@code RangedBowAttackGoal}, full power). */
    static final int BOW_DRAW_TICKS = 20;
    /** A crossbow goal waits 20 to 39 ticks after charging before it fires. */
    static final double CROSSBOW_WAIT_TICKS = 29.5;
    /** A crossbow bolt within about 11 blocks: {@code ceil(1.6 × 2.0)}. */
    static final double CROSSBOW_DAMAGE = 4.0;
    static final double TRIDENT_DAMAGE = 8.0;
    /** Poison ticks once every 25 ticks at level I; wither every 40, and every 20 at II; fire 20. */
    static final int POISON_TICKS = 25;
    static final int WITHER_TICKS = 40;
    static final int WITHER_II_TICKS = 20;
    static final int BURN_TICKS = 20;
    /** Contact damage every tick lands once per hurt-immunity window. */
    static final int CONTACT_TICKS = 10;

    private MobAttacks() {
    }

    public static Attack of(Mob mob) {
        double attr = value(mob, Attributes.ATTACK_DAMAGE);
        int difficulty = mob.level().getDifficulty().getId(); // 0 peaceful … 3 hard
        ItemStack hand = mob.getMainHandItem();
        switch (species(mob)) {
            case "minecraft:creeper":
                return Attack.NONE; // doHurtTarget does nothing; the blast is its own term
            case "minecraft:slime":
                return attr <= 1.0 ? Attack.NONE : new Attack(attr, CONTACT_TICKS, false, 0);
            case "minecraft:magma_cube":
                return new Attack(attr + 2.0, CONTACT_TICKS, false, 0);
            case "minecraft:blaze":
                // Three fireballs of 5 every 178 ticks, 6 apart: immunity drops the middle one.
                return new Attack(5.0, 89.0, false, BURN_TICKS);
            case "minecraft:ghast":
                return new Attack(10.0, 60.0, false, 0); // max(6 fireball, 6–15 blast)
            case "minecraft:shulker":
                return new Attack(4.0, 65.0, false, 0);
            case "minecraft:guardian":
                return new Attack(6.0, 90.0, false, 0);
            case "minecraft:elder_guardian":
                return new Attack(8.0, 70.0, false, 0);
            case "minecraft:phantom":
                return new Attack(attr, 190.0, false, 0); // a swoop every 160–220 ticks
            case "minecraft:vex":
                // One blow per charge and no cooldown in code: measured 40–250 ticks apart,
                // median 80, against a Person standing still (2026-09-30).
                return new Attack(attr, 80.0, false, 0);
            case "minecraft:evoker":
                return new Attack(6.0, 100.0, true, 0); // fangs, indirect magic
            case "minecraft:witch":
                // Poison first, then Harming (6, magic) every 60 ticks while it is poisoned.
                return new Attack(6.0, 60.0, true, POISON_TICKS);
            case "minecraft:wither":
                // Three heads, a skull of 8 each every 40–59 ticks; Wither II from Normal up.
                return new Attack(8.0, 49.5 / 3.0, false, difficulty >= 2 ? WITHER_II_TICKS : 0);
            case "minecraft:breeze":
                return new Attack(1.0, 30.0, false, 0);
            case "minecraft:hoglin":
            case "minecraft:zoglin":
                // attr/2 + nextInt(attr): 3–8, mean 5.5 for an adult; a baby's cooldown is 15.
                return new Attack(attr / 2.0 + (Math.floor(attr) - 1.0) / 2.0,
                        mob.isBaby() ? 15.0 : 40.0, false, 0);
            case "minecraft:iron_golem":
                return new Attack(attr / 2.0 + (Math.floor(attr) - 1.0) / 2.0, 20.0, false, 0);
            case "minecraft:warden":
                return new Attack(attr, 18.0, false, 0); // melee; the sonic boom yields to it
            case "minecraft:piglin":
                return hand.getItem() instanceof CrossbowItem
                        ? crossbow(mob, hand) : new Attack(attr, 20.0, false, 0);
            case "minecraft:piglin_brute":
                return new Attack(attr, 20.0, false, 0);
            case "minecraft:bee":
                // One sting, then it has none: the poison is what stays.
                return new Attack(attr, 600.0, false, difficulty >= 2 ? POISON_TICKS : 0);
            case "minecraft:llama":
            case "minecraft:trader_llama":
                return new Attack(1.0, goals(mob, attr, difficulty, hand).intervalTicks(), false, 0);
            case "minecraft:cave_spider":
                return goals(mob, attr, difficulty, hand).leaving(difficulty >= 2 ? POISON_TICKS : 0);
            case "minecraft:wither_skeleton":
                return goals(mob, attr, difficulty, hand).leaving(WITHER_TICKS);
            case "minecraft:bogged":
                return goals(mob, attr, difficulty, hand).leaving(POISON_TICKS);
            default:
                return goals(mob, attr, difficulty, hand);
        }
    }

    /** The attack its goals hold, or a blow of its attack damage a second if none says. */
    private static Attack goals(Mob mob, double attr, int difficulty, ItemStack hand) {
        Attack melee = null;
        for (WrappedGoal wrapped : ((MobGoalSelectorAccessor) mob).anima$goalSelector().getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (goal instanceof RangedBowAttackGoal<?> bow && hand.getItem() instanceof BowItem) {
                int pause = ((RangedBowAttackGoalAccessor) bow).anima$attackIntervalMin();
                return new Attack(arrow(difficulty), BOW_DRAW_TICKS + pause, false, 0);
            }
            if (goal instanceof RangedCrossbowAttackGoal<?> && hand.getItem() instanceof CrossbowItem) {
                return crossbow(mob, hand);
            }
            if (goal instanceof RangedAttackGoal ranged) {
                RangedAttackGoalAccessor bounds = (RangedAttackGoalAccessor) ranged;
                double interval = (bounds.anima$attackIntervalMin() + bounds.anima$attackIntervalMax()) / 2.0;
                if (hand.is(Items.TRIDENT)) {
                    return new Attack(TRIDENT_DAMAGE, interval, false, 0);
                }
                if (melee == null) {
                    melee = new Attack(attr, interval, false, 0);
                }
            }
            if (goal instanceof MeleeAttackGoal fist) {
                melee = new Attack(attr, ((MeleeAttackGoalInvoker) fist).anima$attackInterval(), false, 0);
            }
        }
        if (melee != null) {
            return melee;
        }
        return attr > 0.0 ? new Attack(attr, 20.0, false, 0) : Attack.NONE;
    }

    /**
     * A mob's arrow at full draw: {@code ceil(speed × (2 + 0.11 × difficulty))}, speed about 1.6 at
     * the target, the rounding up adding half a point on average.
     */
    static double arrow(int difficulty) {
        return 1.6 * (2.0 + 0.11 * difficulty) + 0.5;
    }

    private static Attack crossbow(Mob mob, ItemStack crossbow) {
        return new Attack(CROSSBOW_DAMAGE,
                CrossbowItem.getChargeDuration(crossbow, mob) + CROSSBOW_WAIT_TICKS, false, 0);
    }

    private static String species(Mob mob) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
    }

    private static double value(Mob mob, Holder<Attribute> attribute) {
        AttributeInstance instance = mob.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }
}
