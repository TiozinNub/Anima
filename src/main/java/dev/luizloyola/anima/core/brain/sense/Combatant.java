package dev.luizloyola.anima.core.brain.sense;

/**
 * How a body stands in a fight: how much it takes to kill, how hard and how often it hits, how fast
 * it can chase, and whether it is about to explode. Read off the body (combat spec, 2026-09-27):
 * health is the blood and grit on it, and a creeper's fuse is what counting since the hiss gives.
 *
 * @param health       hit points now
 * @param maxHealth    hit points when whole
 * @param armor        armour points, against a blow ({@link #afterArmor})
 * @param toughness    armour toughness
 * @param damage       what one of its hits deals before the victim's armour
 * @param hitsPerSecond how often it lands one when it can: a player's charge rate, a mob's cooldown
 * @param pace         blocks a tick at its best chasing or running gait
 * @param fuse         0 to 1: how far a creeper's fuse has run; 0 for everything else
 * @param blastReach   blocks within which its explosion hurts; 0 for everything that does not
 * @param entry        how it gets past a wall and a shut door (shelter spec, 2026-09-28)
 * @param small        whether it fits a gap one block wide and one high
 * @param shoots       whether it hurts from range: a bow or a trident in hand, or a species that
 *                     shoots bare-handed (a blaze, a ghast)
 */
public record Combatant(double health, double maxHealth, double armor, double toughness,
                        double damage, double hitsPerSecond, double pace,
                        double fuse, double blastReach, Entry entry, boolean small, boolean shoots) {

    /** How a body gets in to somebody behind walls. */
    public enum Entry {
        /** Walls and shut doors stop it: most things. */
        WALKS,
        /** It opens wooden doors: a piglin, a raiding vindicator. Only no way in at all stops it. */
        OPENS_DOORS,
        /** Nothing stops it: a vex, an enderman, a player who digs. */
        PASSES_WALLS
    }

    /** A walker of ordinary size that does not shoot — the fight numbers alone. */
    public Combatant(double health, double maxHealth, double armor, double toughness,
                     double damage, double hitsPerSecond, double pace,
                     double fuse, double blastReach) {
        this(health, maxHealth, armor, toughness, damage, hitsPerSecond, pace, fuse, blastReach,
                Entry.WALKS, false, false);
    }

    /** Damage a second at full rate, against a victim wearing this much armour. */
    public double damagePerSecondAgainst(double victimArmor, double victimToughness) {
        return afterArmor(damage, victimArmor, victimToughness) * hitsPerSecond;
    }

    /**
     * What a hit of {@code damage} leaves after armour — vanilla's {@code CombatRules}: the armour
     * that counts shrinks as the hit grows, never below a fifth of it nor above 20 points, and
     * each point takes 4%. Armour-piercing enchantments are left out.
     */
    public static double afterArmor(double damage, double armor, double toughness) {
        double perToughness = 2.0 + toughness / 4.0;
        double counted = Math.max(armor * 0.2, Math.min(20.0, armor - damage / perToughness));
        return damage * (1.0 - counted / 25.0);
    }
}
