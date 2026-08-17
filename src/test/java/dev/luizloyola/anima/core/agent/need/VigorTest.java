package dev.luizloyola.anima.core.agent.need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.TestSpecies;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The first need whose value is a composite — so this is where the reason machinery is actually
 * proved rather than asserted: health plus what is acting on it, itemised, with the empty groups
 * still present.
 */
class VigorTest {

    private final Metabolism metabolism = new Metabolism();
    private final List<Effects.Effect> applied = new ArrayList<>();
    private final Vigor vigor = new Vigor(metabolism, () -> List.copyOf(applied),
            () -> TestSpecies.PROFILE);

    /** Pushes hit points the way the body does, on the tick it already reports the gamerule on. */
    private void health(float health) {
        metabolism.tick(false, health, 20.0F);
    }

    @Test
    void withNothingAppliedItIsJustHitPoints() {
        health(16.0F);
        assertEquals(16.0, vigor.value());
        assertEquals("healthy", vigor.level().key());
        // The number glides where the name steps: four points down from full is four fifths of the
        // way to the `hurt` corner's 0.30, and still called healthy.
        assertEquals(0.24, vigor.pressure(), 1e-9);
        health(20.0F);
        assertEquals(0.0, vigor.pressure(), "an untouched body is not asking for anything");
    }

    @Test
    void aDebuffDragsItDownAndABuffHoldsItUp() {
        health(10.0F);
        applied.add(new Effects.Effect("effect.minecraft.weakness", false, 1));
        assertEquals(9.0, vigor.value(), "one level of harm is one hit point of staying power");

        applied.clear();
        applied.add(new Effects.Effect("effect.minecraft.strength", true, 2));
        assertEquals(12.0, vigor.value(), "and it counts per level, not per effect");
    }

    /**
     * Past {@code healthy} the ramp has nothing left to interpolate toward and pins at full
     * pressure — a body given Strength would read as if it were dying.
     */
    @Test
    void aBuffCannotMakeABodyHealthierThanHealthy() {
        health(20.0F);
        applied.add(new Effects.Effect("effect.minecraft.strength", true, 3));
        assertEquals(20.0, vigor.value());
        assertEquals(0.0, vigor.pressure());
        assertEquals("healthy", vigor.level().key());
    }

    @Test
    void theLevelsStepDownWithTheReading() {
        health(20.0F);
        assertEquals("healthy", vigor.level().key());
        health(15.0F);
        assertEquals("hurt", vigor.level().key(), "you are hurt AT the boundary, not past it");
        health(16.0F);
        assertEquals("healthy", vigor.level().key(), "and keep the better name until you reach it");
        health(10.0F);
        assertEquals("wounded", vigor.level().key());
        health(6.0F);
        assertEquals("dying", vigor.level().key());
    }

    /**
     * What the bands are actually FOR: "is anybody on red?", answered without naming a need. On red
     * has to arrive while it is still worth acting on — three hearts, not one.
     */
    @Test
    void onRedMeansTheNextHitProbablyKillsThem() {
        health(20.0F);
        assertEquals(Severity.COMFORTABLE, vigor.severity());
        health(15.0F);
        assertEquals(Severity.MILD, vigor.severity());
        health(10.0F);
        assertEquals(Severity.URGENT, vigor.severity());
        health(6.0F);
        assertEquals(Severity.CRITICAL, vigor.severity(), "three hearts is on red");
        health(7.0F);
        assertEquals(Severity.URGENT, vigor.severity(), "and just above it is not");
    }

    /** The acceptance readout of the needs design, in the order it prints. */
    @Test
    void theItemisationIsHealthThenWhatIsActingOnIt() {
        health(16.0F);
        applied.add(new Effects.Effect("effect.minecraft.strength", true, 1));

        List<ReasonGroup> groups = vigor.reasons();
        assertEquals(3, groups.size());

        assertEquals(List.of(new Reason(NeedKind.REASON_VALUE, Metabolism.HEALTH_NAME_KEY, 16.0)),
                groups.get(0).reasons(), "\"Health is 16\"");

        assertTrue(groups.get(1).isEmpty(), "nothing is dragging them down...");
        assertEquals("anima.needs.vigor.debuffs.none", groups.get(1).emptyKey(),
                "...and the group still prints, which is what \"No debuffs\" means");

        assertFalse(groups.get(2).isEmpty());
        assertEquals(new Reason("anima.needs.vigor.effect", "effect.minecraft.strength", 1.0),
                groups.get(2).reasons().get(0), "\"Has Strength applied to them\"");
    }

    /**
     * The other half of that readout: a hurt body with a debuff and no buffs. Both empty groups and
     * populated ones are the same three families in the same order, so a reader is never comparing
     * two differently-shaped lists.
     */
    @Test
    void aHurtBodyItemisesTheSameThreeFamilies() {
        health(16.0F);
        applied.add(new Effects.Effect("effect.minecraft.weakness", false, 1));

        List<ReasonGroup> groups = vigor.reasons();
        assertEquals("hurt", vigor.level().key(),
                "the debuff alone is what takes 16 across the boundary");
        assertEquals(-1.0, groups.get(1).reasons().get(0).amount(), "the debuff, signed");
        assertTrue(groups.get(2).isEmpty());
        assertEquals("anima.needs.vigor.buffs.none", groups.get(2).emptyKey());
    }

    @Test
    void aGaugeWhoseNumberHasNoPartsExplainsNothing() {
        assertTrue(new FoodNeed(metabolism, () -> TestSpecies.PROFILE).reasons().isEmpty(),
                "and a readout prints its value either way");
    }

    /** Vigor is a modulator, and the registry is where that is written down. */
    @Test
    void itIsDeclaredAsSomethingThatWeighsRatherThanSomethingThatWants() {
        assertEquals(1, NeedKind.VIGOR.bindings().size());
        Binding binding = NeedKind.VIGOR.binding("flee_or_fight");
        assertEquals(Binding.Verb.MODULATE, binding.verb());
        assertEquals(NeedKind.VIGOR, binding.need());
    }
}
