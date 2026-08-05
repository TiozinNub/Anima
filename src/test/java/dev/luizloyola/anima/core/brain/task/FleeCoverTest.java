package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Breaking line of sight, and knowing when not to bother.
 *
 * <p>Cover is a tactic, not a drive: <em>how</em> a body flees, chosen where the destination is.
 * Worth it only against something that shoots — against a zombie, a wall is standing still while
 * it walks round.
 */
class FleeCoverTest {

    private final FakeContext ctx = new FakeContext();
    private final FakeProbe probe = ctx.percepts.blocks;

    @BeforeEach
    void placeThePerson() {
        ctx.percepts.position = new Pos(0, 64, 0);
    }

    private Being threat(String species, Being.Gear gear, Pos at, double distance) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.MONSTER, species, "", null,
                at, distance, 1, 0, false, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, true, gear,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    /** The goal a flee leg picked, read out of the GoTo it decomposed to. */
    private Pos fleeTarget() {
        List<Task> plan = new FleeStep().methods().get(0).decompose(ctx);
        assertEquals(1, plan.size());
        String described = ((PrimitiveTask) plan.get(0)).describe();
        Matcher m = GOAL.matcher(described);
        assertTrue(m.find(), "not a goto: " + described);
        return new Pos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    private static final Pattern GOAL =
            Pattern.compile("goto \\((-?\\d+), (-?\\d+), (-?\\d+)\\)");

    /**
     * Marks the whole left half of the escape fan as out of any line — "there is a wall over
     * there". Which exact cell the search settles on is its business; that it settles on one of
     * them is the behaviour.
     */
    private void putAWallToTheWest() {
        // Far enough west that the ORDINARY escape (straight away, plus a couple of blocks of
        // jitter) can never land in it by accident — so a target inside the wall means the search
        // ran, not that a die came up left.
        for (int x = -12; x <= -5; x++) {
            for (int z = 0; z <= 14; z++) {
                probe.hide(new Pos(x, 64, z));
            }
        }
    }

    @Test
    @DisplayName("against something that shoots, it runs to where no line reaches")
    void aShooterIsFledByBreakingLineOfSight() {
        Pos archer = new Pos(0, 64, -10);
        ctx.percepts.beings = List.of(threat("skeleton", Being.Gear.NONE, archer, 10.0));
        putAWallToTheWest();

        Pos target = fleeTarget();
        assertFalse(probe.visibleFromEyes(target),
                "cover existed along the escape heading and was not taken: " + target);
        assertTrue(target.z() > 0, "and it is still away from the archer: " + target);
    }

    @Test
    @DisplayName("against something with claws, it just runs — a wall buys nothing")
    void aMeleeThreatIsFledByRunning() {
        Pos zombie = new Pos(0, 64, -10);
        ctx.percepts.beings = List.of(threat("zombie", Being.Gear.NONE, zombie, 10.0));
        putAWallToTheWest(); // cover exists, and is ignored

        Pos target = fleeTarget();
        assertTrue(target.z() > 0, "the escape must still be away from the zombie: " + target);
        assertTrue(probe.visibleFromEyes(target),
                "standing behind a wall from a zombie is standing still while it walks around");
    }

    @Test
    @DisplayName("a visibly held bow makes a shooter of anything")
    void heldRangedGearCountsWhateverTheSpeciesIs() {
        Being armed = threat("zombie", new Being.Gear(false, true, false, false, false),
                new Pos(0, 64, -10), 10.0);
        ctx.percepts.beings = List.of(armed);
        putAWallToTheWest();

        assertFalse(probe.visibleFromEyes(fleeTarget()),
                "the item check is what catches an archer nobody declared");
    }

    @Test
    @DisplayName("it does not flee one thing by running into a worse one")
    void theGapBetweenTwoThreatsIsNotEmptyJustBecauseNothingIsInTheMiddle() {
        // Two zombies north-west and north-east: their weighted centre is due north, so the old
        // away-vector fled exactly between them, into a creeper.
        ctx.percepts.beings = List.of(
                threat("zombie", Being.Gear.NONE, new Pos(-8, 64, -8), 11.3),
                threat("zombie", Being.Gear.NONE, new Pos(8, 64, -8), 11.3),
                threat("creeper", Being.Gear.NONE, new Pos(0, 64, 12), 12.0));

        Pos target = fleeTarget();

        double toCreeper = Math.hypot(target.x() - 0, target.z() - 12);
        assertTrue(toCreeper > 6.0,
                "fled straight at the creeper it could see, ending up " + toCreeper
                        + " blocks from it: " + target);
    }

    @Test
    @DisplayName("a fright it can no longer see still steers it, faintly")
    void aRememberedDangerStillCounts() {
        // Nothing perceived to the north but a creeper was there a moment ago. Running from a
        // zombie to the south should still lean away from it.
        long now = 10_000L;
        ctx.percepts.time = now;
        ctx.knowledge.note(new dev.luizloyola.anima.core.brain.knowledge.PoiMemory(
                        dev.luizloyola.anima.core.brain.knowledge.PoiKind.DANGER, "creeper",
                        java.util.UUID.randomUUID(), new Pos(0, 64, 12),
                        dev.luizloyola.anima.core.brain.knowledge.Region.of(new Pos(0, 64, 12)),
                        1, false, now - 600),
                dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge.maxPerKind(
                        dev.luizloyola.anima.core.agent.TestSpecies.PROFILE));
        ctx.percepts.beings = List.of(
                threat("zombie", Being.Gear.NONE, new Pos(0, 64, -10), 10.0));

        Pos target = fleeTarget();

        double toRemembered = Math.hypot(target.x() - 0, target.z() - 12);
        assertTrue(toRemembered > 6.0,
                "ran straight back over where the creeper was, " + toRemembered
                        + " blocks from it: " + target);
    }

    @Test
    @DisplayName("while calm, it wanders away from where something frightening was")
    void aCalmBodyStillAvoidsARememberedFright() {
        long now = 10_000L;
        ctx.percepts.time = now;
        ctx.percepts.beings = List.of(); // nothing perceived at all — she is pottering
        ctx.knowledge.note(new dev.luizloyola.anima.core.brain.knowledge.PoiMemory(
                        dev.luizloyola.anima.core.brain.knowledge.PoiKind.DANGER, "creeper",
                        java.util.UUID.randomUUID(), new Pos(6, 64, 0),
                        dev.luizloyola.anima.core.brain.knowledge.Region.of(new Pos(6, 64, 0)),
                        1, false, now),
                dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge.maxPerKind(
                        dev.luizloyola.anima.core.agent.TestSpecies.PROFILE));

        // Over many strolls the average distance kept from the remembered creeper should beat a
        // uniform roll, or the memory only ever changes how she runs. Most beats are a pause, so
        // only the ones that picked somewhere count. One generator drawn from repeatedly: Random
        // seeded with small consecutive numbers returns almost the same first double every time,
        // so a seed-per-roll loop rolled 0.73 two hundred times and never walked once.
        double total = 0.0;
        int walks = 0;
        WanderStep step = new WanderStep(8);
        for (int i = 0; i < 400; i++) {
            for (Task task : step.methods().get(0).decompose(ctx)) {
                if (task instanceof PrimitiveTask primitive) {
                    Matcher m = GOAL.matcher(primitive.describe());
                    if (m.find()) {
                        walks++;
                        total += Math.hypot(Integer.parseInt(m.group(1)) - 6,
                                Integer.parseInt(m.group(3)) - 0);
                    }
                }
            }
        }
        assertTrue(walks > 20, "not enough walk beats to say anything: " + walks);
        double average = total / walks;
        assertTrue(average > 6.5,
                "an unbiased roll around a spot 6 blocks away averages about 6; a wary one "
                        + "should keep further off than that: " + average);
    }

    @Test
    @DisplayName("with nowhere to hide it still runs rather than standing still")
    void noCoverMeansTheOrdinaryEscape() {
        ctx.percepts.beings = List.of(
                threat("skeleton", Being.Gear.NONE, new Pos(0, 64, -10), 10.0));

        Pos target = fleeTarget();
        assertTrue(target.z() > 0, "no cover anywhere must fall back to running away: " + target);
    }
}
