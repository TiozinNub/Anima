package dev.luizloyola.anima.compat.agent;

import dev.luizloyola.anima.core.brain.sense.Combatant;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.Items;
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

    /** What nothing stops: a vex has no collision while it moves, an enderman teleports. */
    private static final Set<String> PASSES_WALLS = Set.of("minecraft:vex", "minecraft:enderman");

    /** What shoots with nothing in its hand to see. */
    private static final Set<String> SHOOTS_BARE_HANDED = Set.of("minecraft:blaze",
            "minecraft:ghast", "minecraft:breeze", "minecraft:shulker", "minecraft:witch",
            "minecraft:wither");

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
                value(body, Attributes.ARMOR_TOUGHNESS), damage, hits, pace, fuse, blastReach,
                entry(body), body.getBbWidth() < 1.0F && body.getBbHeight() < 1.0F, shoots(body));
    }

    /**
     * How {@code body} gets in behind walls (shelter spec, decision 14): read off it, so a modded
     * mob answers for itself. A zombie's navigation opens doors only to walk up to one it means to
     * break, which is heard, not read (decision 9).
     */
    private static Combatant.Entry entry(LivingEntity body) {
        if (!(body instanceof Mob mob)) {
            return Combatant.Entry.PASSES_WALLS; // a player digs
        }
        if (mob.noPhysics || PASSES_WALLS.contains(species(mob))) {
            return Combatant.Entry.PASSES_WALLS;
        }
        if (mob.getNavigation().getNodeEvaluator().canOpenDoors()
                && !mob.getType().builtInRegistryHolder().is(EntityTypeTags.ZOMBIES)) {
            return Combatant.Entry.OPENS_DOORS;
        }
        return Combatant.Entry.WALKS;
    }

    /** Whether it hurts from range: a bow, a crossbow or a trident in hand, or a bare-handed shooter. */
    private static boolean shoots(LivingEntity body) {
        return body instanceof Mob mob
                && (mob.getMainHandItem().getItem() instanceof ProjectileWeaponItem
                        || mob.getMainHandItem().is(Items.TRIDENT)
                        || SHOOTS_BARE_HANDED.contains(species(mob)));
    }

    private static String species(Mob mob) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
    }

    private static double value(LivingEntity body, Holder<Attribute> attribute) {
        AttributeInstance instance = body.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }
}
