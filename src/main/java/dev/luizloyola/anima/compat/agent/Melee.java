package dev.luizloyola.anima.compat.agent;

import dev.luizloyola.anima.mixin.LivingEntityAttackStrengthAccessor;
import java.util.function.Predicate;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/**
 * A player's melee blow, for a body that is not a player. {@code Player.attack} runs only for a
 * player, so this is its copy, kept to vanilla's order and numbers (26.1.2): damage from the
 * {@code attack_damage} attribute and the enchantments, scaled by the charge; knockback, a
 * sprinting blow's extra shove, the sword's sweep, the item's own wear and post-hit effects, the
 * sounds, the damage particles, and 0.1 of exhaustion charged by the caller.
 *
 * <p>Left out on purpose: the critical hit, which a player gets by falling onto the blow and an
 * agent will get only as a skill (combat spec, Later); and spears, whose charged jab is its own
 * attack.
 *
 * <p>The charge is vanilla's own counter, the one {@code Player.tick} advances. A body's owner
 * advances it; {@link Arms} starts it over on every swing, as {@code ServerPlayer.swing} does.
 */
public final class Melee {

    private Melee() {
    }

    /** One more tick of charge. */
    public static void advanceCharge(LivingEntity body) {
        LivingEntityAttackStrengthAccessor ticker = (LivingEntityAttackStrengthAccessor) body;
        ticker.anima$setAttackStrengthTicker(ticker.anima$attackStrengthTicker() + 1);
    }

    /** Starts the charge over — what a player's hand changing to a different item does. */
    public static void resetCharge(LivingEntity body) {
        ((LivingEntityAttackStrengthAccessor) body).anima$setAttackStrengthTicker(0);
    }

    /** The charge counter itself, for saving. */
    public static int chargeTicks(LivingEntity body) {
        return ((LivingEntityAttackStrengthAccessor) body).anima$attackStrengthTicker();
    }

    public static void setChargeTicks(LivingEntity body, int ticks) {
        ((LivingEntityAttackStrengthAccessor) body).anima$setAttackStrengthTicker(ticks);
    }

    /** {@code Player.getAttackStrengthScale(0.5)}: 0 just after a swing, 1 when fully charged. */
    public static float charge(LivingEntity body) {
        float delay = (float) (1.0 / body.getAttributeValue(Attributes.ATTACK_SPEED) * 20.0);
        return Mth.clamp((chargeTicks(body) + 0.5F) / delay, 0.0F, 1.0F);
    }

    /**
     * Whether {@code target} is within arm's length of {@code body}'s eyes: vanilla's default
     * attack range, the {@code entity_interaction_range} attribute measured to the target's box.
     */
    public static boolean withinReach(LivingEntity body, LivingEntity target) {
        double range = body.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        return target.getBoundingBox().distanceToSqr(body.getEyePosition()) <= range * range;
    }

    /**
     * Swings at {@code target} the way {@code Player.attack} does. True when the target took the
     * blow.
     *
     * @param sweepable who besides the target a sweep may catch; vanilla's own exclusions (the
     *     striker, the target, allies, marker stands, anything three blocks off) are applied first
     */
    public static boolean strike(ServerLevel level, LivingEntity body, LivingEntity target,
                                 Predicate<LivingEntity> sweepable) {
        if (!target.isAttackable() || target.skipAttackInteraction(body)) {
            return false;
        }
        ItemStack weapon = body.getWeaponItem();
        float baseDamage = (float) body.getAttributeValue(Attributes.ATTACK_DAMAGE);
        //? if >=26.2 {
        /*DamageSource source = weapon.getDamageSource(body);
        *///?} else {
        DamageSource source = weapon.getDamageSource(body,
                () -> body.damageSources().mobAttack(body));
        //?}
        float charge = charge(body);
        float magicBoost = charge
                * (EnchantmentHelper.modifyDamage(level, weapon, target, source, baseDamage) - baseDamage);
        baseDamage *= 0.2F + charge * charge * 0.8F;
        Arms.swingToAttack(body, InteractionHand.MAIN_HAND); // starts the charge over
        if (baseDamage <= 0.0F && magicBoost <= 0.0F) {
            return false;
        }
        boolean fullStrength = charge > 0.9F;
        boolean knockbackBlow = body.isSprinting() && fullStrength;
        if (knockbackBlow) {
            sound(level, body, SoundEvents.PLAYER_ATTACK_KNOCKBACK);
        }
        baseDamage += weapon.getItem().getAttackDamageBonus(target, baseDamage, source);
        // A player's getSpeed() is its movement_speed attribute; a plain body's is a field the
        // mob AI sets, which an agent has none of.
        double speed = body.getAttributeValue(Attributes.MOVEMENT_SPEED);
        boolean sweep = fullStrength && !knockbackBlow && body.onGround()
                && body.getDeltaMovement().horizontalDistanceSqr() < Mth.square(speed * 2.5)
                && weapon.is(ItemTags.SWORDS);
        float healthBefore = target.getHealth();
        Vec3 movementBefore = target.getDeltaMovement();
        // Vanilla shoves along the attacker's yaw; a body is always facing what it swings at, so
        // the line between them is the same direction without trusting the yaw to have caught up.
        Vec3 facing = new Vec3(target.getX() - body.getX(), 0.0, target.getZ() - body.getZ()).normalize();
        if (!target.hurtServer(level, source, baseDamage + magicBoost)) {
            sound(level, body, SoundEvents.PLAYER_ATTACK_NODAMAGE);
            return false;
        }
        float knockback = (float) body.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
        knockback = EnchantmentHelper.modifyKnockback(level, weapon, target, source, knockback) / 2.0F
                + (knockbackBlow ? 0.5F : 0.0F);
        if (knockback > 0.0F) {
            //? if >=26.2 {
            /*target.knockback(knockback, -facing.x, -facing.z, source, baseDamage + magicBoost, true);
            *///?} else {
            target.knockback(knockback, -facing.x, -facing.z);
            //?}
            body.setDeltaMovement(body.getDeltaMovement().multiply(0.6, 1.0, 0.6));
            body.setSprinting(false);
        }
        // A player's client owns its own motion: the shove is sent, then the server's copy put back.
        //? if >=26.3 {
        /*if (target instanceof ServerPlayer player && player.syncVelocity) {
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
            player.syncVelocity = false;
            player.setDeltaMovement(movementBefore);
        }
        *///?} else {
        if (target instanceof ServerPlayer player && player.hurtMarked) {
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
            player.hurtMarked = false;
            player.setDeltaMovement(movementBefore);
        }
        //?}
        if (sweep) {
            sweep(level, body, target, weapon, source, baseDamage, charge, facing, sweepable);
        } else {
            sound(level, body, fullStrength
                    ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK);
        }
        if (magicBoost > 0.0F) {
            level.getChunkSource().sendToTrackingPlayers(body, new ClientboundAnimatePacket(target, 5));
        }
        body.setLastHurtMob(target);
        boolean hurtEnemy = weapon.hurtEnemy(target, body);
        EnchantmentHelper.doPostAttackEffectsWithItemSource(level, target, source, weapon);
        if (hurtEnemy) {
            weapon.postHurtEnemy(target, body); // the wear; a broken weapon empties the hand
        }
        float dealt = healthBefore - target.getHealth();
        if (dealt > 2.0F) {
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY(0.5),
                    target.getZ(), (int) (dealt * 0.5), 0.1, 0.0, 0.1, 0.2);
        }
        return true;
    }

    private static void sweep(ServerLevel level, LivingEntity body, LivingEntity target,
                              ItemStack weapon, DamageSource source, float baseDamage, float charge,
                              Vec3 facing, Predicate<LivingEntity> sweepable) {
        sound(level, body, SoundEvents.PLAYER_ATTACK_SWEEP);
        float damage = 1.0F + (float) body.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO) * baseDamage;
        for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class,
                target.getBoundingBox().inflate(1.0, 0.25, 1.0))) {
            if (nearby == body || nearby == target || body.isAlliedTo(nearby)
                    || nearby instanceof ArmorStand stand && stand.isMarker()
                    || body.distanceToSqr(nearby) >= 9.0 || !sweepable.test(nearby)) {
                continue;
            }
            float dealt = EnchantmentHelper.modifyDamage(level, weapon, nearby, source, damage) * charge;
            if (nearby.hurtServer(level, source, dealt)) {
                //? if >=26.2 {
                /*nearby.knockback(0.4F, -facing.x, -facing.z, source, dealt);
                *///?} else {
                nearby.knockback(0.4F, -facing.x, -facing.z);
                //?}
                EnchantmentHelper.doPostAttackEffects(level, nearby, source);
            }
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, body.getX() + facing.x, body.getY(0.5),
                body.getZ() + facing.z, 0, facing.x, 0.0, facing.z, 0.0);
    }

    private static void sound(ServerLevel level, LivingEntity body, SoundEvent sound) {
        level.playSound(null, body.getX(), body.getY(), body.getZ(), sound, body.getSoundSource(),
                1.0F, 1.0F);
    }
}
