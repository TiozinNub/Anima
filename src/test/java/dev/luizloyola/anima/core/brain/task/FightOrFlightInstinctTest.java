package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.sense.TestDanger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.luizloyola.anima.core.brain.instinct.FightOrFlightInstinct;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FightOrFlightInstinct}'s pressure curve: a linear ramp from reach down to contact,
 * multiplied by the species' danger weight and the visible-gear modifiers, boosted and capped
 * at {@code 1.0} for a threat measurably CLOSING IN, then the MAX across every perceived
 * aggressive being. Non-aggressive and not-yet-made-out beings exert nothing, and a RANGED
 * threat's fear starts farther out.
 */
class FightOrFlightInstinctTest {

    private final FakeContext ctx = new FakeContext();

    /** The standard threat: an identified bare-handed zombie — danger weight exactly 1.0. */
    private static Being threatAt(double distance, boolean approaching) {
        return FakePercepts.monsterAt(new Pos(0, 64, 0), distance, approaching);
    }

    private static Being speciesAt(String species, double distance, Being.Gear gear) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.MONSTER, species, "",
                null, new Pos(0, 64, 0), distance, Being.HUMANOID_EYE_HEIGHT, false, 1, 0, false,
                List.of(),
                Being.Activity.IDLE, Being.Locomotion.STILL, false, false, false, false, false,
                true, gear, Being.Identified.SPECIES, Being.Awareness.SEEN);
    }

    @Test
    void nothingAggressiveMeansNoPressure() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        ctx.percepts.beings = List.of();
        assertEquals(0.0, flee.pressure(ctx));
    }

    @Test
    void aThreatAtTheEdgeOfRangeExertsNoPressure() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        ctx.percepts.beings = List.of(threatAt(FightOrFlightInstinct.range(TestSpecies.PROFILE), false));
        assertEquals(0.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void aPassiveThreatCrossesThePreemptLineAtEightPointEightBlocks() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        ctx.percepts.beings = List.of(threatAt(8.8, false));
        assertEquals(0.6, flee.pressure(ctx), 1e-6);
    }

    @Test
    void contactIsFullPressure() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        ctx.percepts.beings = List.of(threatAt(4.0, false));
        assertEquals(1.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void anApproachingThreatCrossesThePreemptLineFurtherOutAtTenPointFiveBlocks() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        ctx.percepts.beings = List.of(threatAt(10.5, true));
        // (16 - 10.5) / 12 = 0.458333... ; * 1.3 (the approach bonus) = 0.595833...
        assertEquals(0.5958333333333333, flee.pressure(ctx), 1e-9);
    }

    @Test
    void approachBonusCapsAtOneEvenAtContact() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        ctx.percepts.beings = List.of(threatAt(4.0, true)); // uncapped would be 1.0 * 1.3 = 1.3
        assertEquals(1.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void pressureIsTheMaxAcrossThreatsSoAFarApproachingThreatCanOutweighANearIdleOne() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        // Further away but closing in (0.5 * 1.3 = 0.65) beats closer but idle (0.5833...).
        ctx.percepts.beings = List.of(threatAt(10.0, true), threatAt(9.0, false));
        assertEquals(0.65, flee.pressure(ctx), 1e-9);
    }

    @Test
    void aScarierSpeciesMultipliesItsWeightIn() {
        // A creeper (danger 1.6) at the same distance a zombie reads 0.5 → 0.8.
        assertEquals(0.8, FightOrFlightInstinct.pressureOf(TestSpecies.PROFILE, TestDanger.TABLE, speciesAt("creeper", 10.0, Being.Gear.NONE)),
                1e-9);
    }

    @Test
    void aRangedSpeciesIsFearedFromFartherOut() {
        // A skeleton shoots, so it is feared from as far as this body can perceive it at all
        // (senses.radius, 24) rather than from its flee range (16). At exactly 16 blocks:
        // (24 - 16) / 12 * 1.2 (skeleton weight) = 0.8 — where a zombie there reads zero.
        assertEquals(0.0, FightOrFlightInstinct.pressureOf(TestSpecies.PROFILE, TestDanger.TABLE, speciesAt("zombie", 16.0, Being.Gear.NONE)), 1e-9);
        assertEquals(0.8, FightOrFlightInstinct.pressureOf(TestSpecies.PROFILE, TestDanger.TABLE, speciesAt("skeleton", 16.0, Being.Gear.NONE)), 1e-9);
    }

    @Test
    void visibleGearMultipliesTheDanger() {
        double bare = FightOrFlightInstinct.pressureOf(TestSpecies.PROFILE, TestDanger.TABLE, speciesAt("zombie", 10.0, Being.Gear.NONE));
        double armed = FightOrFlightInstinct.pressureOf(TestSpecies.PROFILE, TestDanger.TABLE, speciesAt("zombie", 10.0,
                new Being.Gear(true, false, true, false, false))); // sword + armor
        assertEquals(0.5, bare, 1e-9);
        assertEquals(0.5 * 1.15 * 1.2, armed, 1e-9);
    }

    @Test
    void theUnmadeOutAndTheCalmExertNothing() {
        // aggressive=false covers both a grazing cow and a masked something (the sensor
        // masks aggression below the species tier): neither prices any fear.
        Being calm = new Being(BeingId.of(UUID.randomUUID()), Being.Kind.PASSIVE, "cow", "",
                null, new Pos(0, 64, 0), 2.0, Being.HUMANOID_EYE_HEIGHT, false, 1, 0, true,
                List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
        assertEquals(0.0, FightOrFlightInstinct.pressureOf(TestSpecies.PROFILE, TestDanger.TABLE, calm));
    }

    @Test
    void failCooldownIsTheEmergencyTenTickOverrideNotTheDefaultHundred() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        assertEquals(10, flee.failCooldown());
        assertEquals(FightOrFlightInstinct.FAIL_COOLDOWN, flee.failCooldown());
    }

    @Test
    void rootIsAFreshFleeStepEachGrant() {
        FightOrFlightInstinct flee = new FightOrFlightInstinct();
        var a = flee.root(ctx);
        var b = flee.root(ctx);
        assertInstanceOf(FleeStep.class, a);
        assertNotSame(a, b, "each grant builds a new tree — never a cached instance");
    }

    @Test
    void describeIsFightOrFlight() {
        assertEquals("fight or flight", new FightOrFlightInstinct().describe());
    }

    // --- the answer: the combat spec's scenes, as numbers -----------------------------------------
    // Vanilla's: a zombie is 20 hp, 2 armour, hits 3 once a second and chases at ~0.117 b/t; a
    // spider 16 hp, hits 2, ~0.198; a creeper hits nothing and blasts out to 6. A Person sprints at
    // ~0.28 b/t. A fist is 1 damage at 4 a second, an iron sword 6 at 1.6.

    private static final Combatant ZOMBIE = new Combatant(20, 20, 2, 0, 3, 1.0, 0.117, 0, 0);
    private static final Combatant SPIDER = new Combatant(16, 16, 0, 0, 2, 1.0, 0.198, 0, 0);
    private static final Combatant BABY_ZOMBIE = new Combatant(20, 20, 2, 0, 3, 1.0, 0.3, 0, 0);

    private static Combatant creeper(double fuse) {
        return new Combatant(20, 20, 0, 0, 0, 0, 0.138, fuse, 6);
    }

    private static Combatant me(double damage, double perSecond, double health) {
        return new Combatant(health, 20, 0, 0, damage, perSecond, 0.28, 0, 0);
    }

    private final FightOrFlightInstinct mind = new FightOrFlightInstinct();

    private Being add(String species, double distance, Combatant them) {
        Being being = speciesAt(species, distance, Being.Gear.NONE);
        List<Being> all = new java.util.ArrayList<>(ctx.percepts.beings);
        all.add(being);
        ctx.percepts.beings = all;
        ctx.percepts.combatants.put(being.id(), them);
        return being;
    }

    private Task next() {
        ctx.percepts.time++; // a new tick: a fresh decision
        return mind.root(ctx);
    }

    @Test
    void armedAndHealthyItFightsAZombie() {
        ctx.percepts.self = me(6, 1.6, 20);
        Being zombie = add("zombie", 3, ZOMBIE);

        Fight fight = assertInstanceOf(Fight.class, next());
        assertEquals(zombie.id(), fight.target());
    }

    @Test
    void emptyHandedItRunsFromAZombieItCanOutpace() {
        ctx.percepts.self = me(1, 4, 20); // 1.25: it would win, only just
        add("zombie", 3, ZOMBIE);

        assertInstanceOf(FleeStep.class, next());
    }

    @Test
    void threeMoreZombiesBreakOffAFightItWasWinning() {
        ctx.percepts.self = me(6, 1.6, 20);
        add("zombie", 3, ZOMBIE);
        Task fight = next();
        assertNull(mind.reconsider(ctx, fight), "one zombie and a sword: keep at it");

        add("zombie", 5, ZOMBIE);
        add("zombie", 6, ZOMBIE);
        add("zombie", 7, ZOMBIE);
        ctx.percepts.time++;
        assertNotNull(mind.reconsider(ctx, fight), "four hit 12 a second; it would lose");
        assertInstanceOf(FleeStep.class, mind.root(ctx));
    }

    @Test
    void aFightAlreadyBegunIsKeptDownToTheLowerLine() {
        ctx.percepts.self = me(1, 4, 20); // 1.25: under the odds to start, over the odds to quit
        Being zombie = add("zombie", 3, ZOMBIE);
        ctx.percepts.time++;
        Task fight = new Fight(zombie.id(), zombie.pos());

        assertNull(mind.reconsider(ctx, fight), "a close fight in hand is not dropped");
        assertInstanceOf(FleeStep.class, mind.root(ctx), "nor would it have been started");
    }

    @Test
    void aTargetGoingDownIsLeftToEndItsOwnFight() {
        ctx.percepts.self = me(6, 1.6, 20);
        Being zombie = add("zombie", 3, ZOMBIE);
        Task fight = next();
        ctx.percepts.combatants.remove(zombie.id()); // dying: no body left to size up
        ctx.percepts.time++;

        assertNull(mind.reconsider(ctx, fight), "the fight reports the kill itself");
    }

    @Test
    void aRunCutShortWhenTheOddsTurn() {
        ctx.percepts.self = me(1, 4, 20);
        Being zombie = add("zombie", 3, ZOMBIE);
        Task run = next();
        assertInstanceOf(FleeStep.class, run);

        ctx.percepts.self = me(6, 1.6, 20); // it drew an iron sword
        ctx.percepts.time++;
        assertNotNull(mind.reconsider(ctx, run));
        assertEquals(zombie.id(), assertInstanceOf(Fight.class, mind.root(ctx)).target());
    }

    @Test
    void atFourHealthAZombieOneBlowFromDeadIsStillFinished() {
        ctx.percepts.self = me(6, 1.6, 4);
        add("zombie", 3, new Combatant(2, 20, 2, 0, 3, 1.0, 0.117, 0, 0));
        assertInstanceOf(Fight.class, next(), "the last hit is worth it");

        ctx.percepts.beings = List.of();
        add("zombie", 3, ZOMBIE);
        assertInstanceOf(FleeStep.class, next(), "a whole zombie at four health is not");
    }

    @Test
    void emptyHandedItFightsASpider() {
        ctx.percepts.self = me(1, 4, 20); // 16 hp at 4 a second, against 2 a second at it
        add("spider", 3, SPIDER);

        assertInstanceOf(Fight.class, next());
    }

    @Test
    void somethingItCannotOutrunIsFoughtAtWorseOdds() {
        ctx.percepts.self = me(1, 4, 20);
        add("zombie", 3, BABY_ZOMBIE);
        assertInstanceOf(Fight.class, next(), "running from something faster buys nothing");

        ctx.percepts.self = new Combatant(20, 20, 0, 0, 1, 4, 0.4, 0, 0);
        assertInstanceOf(FleeStep.class, next(), "the same odds, but it can get away");
    }

    @Test
    void aChaserOnlyAsFastAsThisBodyDoesNotCornerIt() {
        ctx.percepts.self = me(1, 4, 20); // 1.25: fought only when cornered
        add("person", 3, new Combatant(20, 20, 2, 0, 3, 1.0, 0.28, 0, 0));
        ctx.percepts.attackers.addAll(ctx.percepts.combatants.keySet());

        assertInstanceOf(FleeStep.class, next(),
                "a player sprints exactly as fast: running holds the gap, so it runs");
    }

    @Test
    void anUnlitCreeperIsFoughtAndALitOneAtArmsLengthIsRunFromInAnyArmour() {
        ctx.percepts.self = me(6, 1.6, 20);
        Being creeper = add("creeper", 3, creeper(0));
        Task fight = next();
        assertInstanceOf(Fight.class, fight, "it hits nothing until it goes off");

        ctx.percepts.self = new Combatant(20, 20, 20, 12, 6, 1.6, 0.28, 0, 0); // full diamond
        ctx.percepts.combatants.put(creeper.id(), creeper(0.9));
        ctx.percepts.time++;
        assertNotNull(mind.reconsider(ctx, fight), "lit at three blocks: break off");
        assertInstanceOf(FleeStep.class, mind.root(ctx));
        assertEquals(1.0, mind.pressure(ctx), 1e-9, "and nothing outbids running from it");
    }

    /** {@code who} moved to {@code distance}, now standing as {@code them}. */
    private void move(Being who, double distance, Combatant them) {
        Being moved = new Being(who.id(), who.kind(), who.species(), who.name(), who.profession(),
                who.pos(), distance, who.eyeHeight(), who.playerControlled(), who.count(),
                who.spread(), who.herdAnimal(), who.members(), who.activity(), who.locomotion(),
                who.sneaking(), who.watching(), who.aimedAt(), who.hailing(), who.approaching(),
                who.aggressive(), who.gear(), who.identified(), who.awareness(), who.held());
        ctx.percepts.beings = List.of(moved);
        ctx.percepts.combatants.put(who.id(), them);
    }

    @Test
    void aLitCreeperIsRunFromUntilItsFuseGoesOutNotJustToTheEdgeOfItsBlast() {
        ctx.percepts.self = me(1, 4, 20);
        Being creeper = add("creeper", 3, creeper(0.9));
        assertInstanceOf(FleeStep.class, next());

        move(creeper, 5, creeper(0.3)); // under the blast line, and still burning
        Task run = next();
        assertInstanceOf(FleeStep.class, run, "turning back here walks into a lit fuse");
        assertNull(mind.reconsider(ctx, run));

        move(creeper, 9, creeper(0.2)); // past 7 blocks its fuse runs back down
        assertInstanceOf(Fight.class, next(), "then it goes back for it");
    }

    @Test
    void theBlastFallsOffFast() {
        assertEquals(0.0, FightOrFlightInstinct.blast(10, creeper(0.05)), 1e-9,
                "ten blocks from one that has just started hissing is safe");
        assertEquals(1.0, FightOrFlightInstinct.blast(1, creeper(1.0)), 1e-9,
                "a block from one about to go is certain");
        assertEquals(0.0, FightOrFlightInstinct.blast(6, creeper(1.0)), 1e-9,
                "past twice its power the explosion hurts nothing");
        assertEquals(0.0, FightOrFlightInstinct.blast(1, ZOMBIE), 1e-9);
    }

    @Test
    void aPlayerWhoHitsIsAnsweredThoughTheirKindWeighsNothing() {
        ctx.danger = TestDanger.TABLE.withOverrides(java.util.Map.of("person", 0.0));
        ctx.percepts.self = new Combatant(20, 20, 10, 0, 7, 1.6, 0.28, 0, 0);
        Being player = add("person", 3, new Combatant(20, 20, 0, 0, 1, 4, 0.28, 0, 0));
        assertEquals(0.0, mind.pressure(ctx), 1e-9, "a player standing there is nobody's enemy");

        ctx.percepts.attackers.add(player.id());
        assertTrue(mind.pressure(ctx) > 0.0, "one who swings is priced as something hostile");
        Fight fight = assertInstanceOf(Fight.class, next());
        assertEquals(player.id(), fight.target());
    }

    @Test
    void theStrongestGearATargetHasShownIsRememberedUntilItIsLostTrackOf() {
        ctx.danger = TestDanger.TABLE.withOverrides(java.util.Map.of("person", 0.0));
        ctx.percepts.self = me(5, 1.6, 20); // a stone sword
        Combatant fists = new Combatant(20, 20, 0, 0, 1, 4, 0.28, 0, 0);
        Combatant netherite = new Combatant(20, 20, 0, 0, 8, 1.6, 0.28, 0, 0);
        Being player = add("person", 3, fists);
        ctx.percepts.attackers.add(player.id());
        assertInstanceOf(Fight.class, next(), "a player with bare fists is worth fighting");

        ctx.percepts.combatants.put(player.id(), netherite);
        assertInstanceOf(FleeStep.class, next(), "one with a netherite sword is not");

        ctx.percepts.combatants.put(player.id(), fists);
        assertInstanceOf(FleeStep.class, next(), "putting it away does not unsee it");

        List<Being> all = ctx.percepts.beings;
        ctx.percepts.beings = List.of();
        next(); // lost track of them
        ctx.percepts.beings = all;
        assertInstanceOf(Fight.class, next(), "and a stranger is judged by what it shows");
    }

    @Test
    void whateverHitThisBodyIsFoughtFirst() {
        ctx.percepts.self = me(6, 1.6, 20);
        add("zombie", 4, ZOMBIE);
        Being biter = add("zombie", 4, ZOMBIE);
        ctx.percepts.attackers.add(biter.id());

        assertEquals(biter.id(), assertInstanceOf(Fight.class, next()).target());
    }

    @Test
    void theZombieBeingFoughtKeepsItsPlaceOverAnEqualOne() {
        ctx.percepts.self = me(6, 1.6, 20);
        Being first = add("zombie", 4, ZOMBIE);
        Task fight = next();
        // Wounded a little: a sixth quicker to kill, which is less than the edge the first one has.
        Being second = add("zombie", 4, new Combatant(16, 20, 2, 0, 3, 1.0, 0.117, 0, 0));
        ctx.percepts.time++;

        assertEquals(first.id(), ((Fight) fight).target());
        assertNull(mind.reconsider(ctx, fight), "no trading places between two alike: " + second);
    }

    @Test
    void aTargetAFightCouldNotReachIsLeftOutForAWhile() {
        ctx.percepts.self = me(6, 1.6, 20);
        add("zombie", 3, ZOMBIE);
        Task fight = next();
        mind.ended(ctx, fight, TaskStatus.FAILED);

        assertInstanceOf(FleeStep.class, next(), "across the river: running beats chasing again");
        ctx.percepts.time += 200;
        assertInstanceOf(Fight.class, next(), "and after a while it tries again");
    }

    @Test
    void aBodyThatCannotSizeItselfUpRuns() {
        add("zombie", 3, ZOMBIE);
        assertInstanceOf(FleeStep.class, next());
    }

    @Test
    void theDoingNamesWhatItFights() {
        ctx.percepts.self = me(6, 1.6, 20);
        add("zombie", 3, ZOMBIE);
        ctx.percepts.time++;

        assertEquals(Deed.of(Doings.FIGHTING, Slot.entity("zombie")), mind.doing(ctx));
    }
}
