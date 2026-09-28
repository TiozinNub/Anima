package dev.luizloyola.anima.compat.agent;

import dev.luizloyola.anima.core.brain.sense.Combatant;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.jspecify.annotations.Nullable;

/**
 * How a body stands in a fight, read off it ({@link Combatant}). The numbers are vanilla's, and
 * where vanilla keeps them in a goal rather than on the body, they are the goal's constants:
 *
 * <ul>
 *   <li>a mob lands a melee hit every 20 ticks ({@code MeleeAttackGoal}), for its
 *       {@code attack_damage}, which already carries its weapon;</li>
 *   <li>a bow or crossbow in a mob's hand is an arrow of about 4 every 40 ticks — a skeleton's
 *       interval below hard difficulty;</li>
 *   <li>a mob chases at about {@code movement_speed² / 0.454} blocks a tick ({@code Mob.setSpeed}
 *       also sets the forward input, so the attribute counts twice); a player or an agent walks at
 *       {@code movement_speed × 2.16} and sprints 1.3 times that;</li>
 *   <li>a creeper's explosion hurts out to twice its power — 3, doubled when charged — and its fuse
 *       is {@code getSwelling}, 0 to 1 over 28 ticks.</li>
 * </ul>
 *
 * <p>Damage to an agent is never scaled by difficulty: vanilla scales a mob's hit only against a
 * player.
 */
public final class Fighters {

    /** Blocks a tick one point of {@code movement_speed} walks a player-shaped body. */
    static final double WALK_PER_SPEED = 2.16;
    static final double SPRINT = 1.3;
    /** A mob's melee cooldown, as hits a second. */
    static final double MOB_HITS_PER_SECOND = 1.0;
    static final double ARROW_DAMAGE = 4.0;
    static final double ARROWS_PER_SECOND = 0.5;
    /** A creeper's explosion power: its default {@code ExplosionRadius}. */
    static final double CREEPER_POWER = 3.0;

    private Fighters() {
    }

    /**
     * {@code body} in a fight, or null while it is dying.
     *
     * @param canSprint for a player-shaped body, whether it can run at a sprint now
     */
    public static @Nullable Combatant read(LivingEntity body, boolean canSprint) {
        if (body.isDeadOrDying()) {
            return null;
        }
        double damage = value(body, Attributes.ATTACK_DAMAGE);
        double hits;
        double pace;
        double fuse = 0.0;
        double blastReach = 0.0;
        if (body instanceof Mob mob) {
            double speed = value(mob, Attributes.MOVEMENT_SPEED);
            pace = speed * speed / 0.454;
            hits = damage > 0.0 ? MOB_HITS_PER_SECOND : 0.0;
            if (mob.getMainHandItem().getItem() instanceof ProjectileWeaponItem) {
                damage = ARROW_DAMAGE;
                hits = ARROWS_PER_SECOND;
            }
            if (mob instanceof Creeper creeper) {
                fuse = Mth.clamp(creeper.getSwelling(1.0F), 0.0F, 1.0F);
                blastReach = 2.0 * CREEPER_POWER * (creeper.isPowered() ? 2.0 : 1.0);
            }
        } else {
            hits = value(body, Attributes.ATTACK_SPEED);
            pace = value(body, Attributes.MOVEMENT_SPEED) * WALK_PER_SPEED * (canSprint ? SPRINT : 1.0);
        }
        return new Combatant(body.getHealth(), body.getMaxHealth(), body.getArmorValue(),
                value(body, Attributes.ARMOR_TOUGHNESS), damage, hits, pace, fuse, blastReach);
    }

    private static double value(LivingEntity body, Holder<Attribute> attribute) {
        AttributeInstance instance = body.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }
}
