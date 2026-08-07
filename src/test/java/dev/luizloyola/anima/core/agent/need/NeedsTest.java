package dev.luizloyola.anima.core.agent.need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.TestSpecies;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The roster: one tick site, one readout, and room for a gauge Anima has never heard of. */
class NeedsTest {

    private static final double DELTA = 1e-9;

    /** A body shaped like a settler's: hunger as a view, company as its own number. */
    private static Needs settler(Metabolism metabolism) {
        return new Needs()
                .add(new FoodNeed(metabolism, () -> TestSpecies.PROFILE))
                .add(new Company(() -> TestSpecies.PROFILE));
    }

    @Test
    @DisplayName("food is a VIEW — one number, two readers, no drift")
    void foodReadsThroughToTheOrgan() {
        Metabolism metabolism = new Metabolism();
        Needs needs = settler(metabolism);

        assertEquals(20.0, needs.value(NeedKind.HUNGER), DELTA,
                "a fresh body spawns fed — and the reading is in FOOD POINTS, which is what an "
                        + "operator tunes and a readout prints");
        assertEquals(0.0, needs.pressure(NeedKind.HUNGER), DELTA);

        metabolism.setFoodLevel(8);
        assertEquals(8.0, needs.value(NeedKind.HUNGER), DELTA, "the gauge moved because the ORGAN did");
        assertEquals(0.6, needs.pressure(NeedKind.HUNGER), DELTA,
                "and pressure is the metabolism's own hunger, unchanged");
        assertEquals(metabolism.hunger(), needs.pressure(NeedKind.HUNGER), DELTA);
    }

    @Test
    @DisplayName("ticking the roster never moves food — the body ticks the organ itself")
    void tickingLeavesTheViewAlone() {
        Metabolism metabolism = new Metabolism();
        Needs needs = settler(metabolism);
        metabolism.setFoodLevel(8);
        for (int i = 0; i < 200; i++) {
            needs.tick();
        }
        assertEquals(8, metabolism.foodLevel(),
                "a roster tick that fed or starved a body would be a second metabolism");
        assertEquals(0.6, needs.pressure(NeedKind.HUNGER), DELTA);
    }

    @Test
    @DisplayName("one tick advances every gauge that owns its number")
    void tickAdvancesTheRealGauges() {
        Needs needs = settler(new Metabolism());
        double before = needs.value(NeedKind.COMPANY);
        needs.tick();
        assertTrue(needs.value(NeedKind.COMPANY) < before, "solitude drained it on the shared beat");
    }

    @Test
    @DisplayName("a need this body does not have exerts no pressure, and is not asked about")
    void absentGaugesAnswerZero() {
        NeedKind warmth = NeedKind.register("test_warmth");
        Needs needs = settler(new Metabolism());

        assertFalse(needs.has(warmth));
        assertTrue(needs.gauge(warmth).isEmpty());
        // The reading that makes a drive portable: an instinct that bids on warmth never
        // bids on a body without it, without first asking whether it has one.
        assertEquals(0.0, needs.pressure(warmth), DELTA);
        assertEquals(0.0, needs.value(warmth), DELTA);
    }

    @Test
    @DisplayName("a body cannot have two answers for the same need")
    void refusesADuplicateKind() {
        Metabolism metabolism = new Metabolism();
        Needs needs = settler(metabolism);
        assertThrows(IllegalStateException.class, () -> needs.add(new FoodNeed(metabolism, () -> TestSpecies.PROFILE)));
    }

    @Test
    @DisplayName("the readout lists what a body feels without knowing what any of it is")
    void describesEveryGaugeInDeclarationOrder() {
        Metabolism metabolism = new Metabolism();
        Needs needs = settler(metabolism);
        metabolism.setFoodLevel(8);

        assertEquals(List.of(NeedKind.HUNGER, NeedKind.COMPANY),
                needs.all().stream().map(Gauge::kind).toList(),
                "declaration order, so a saved file and a printed line agree between runs");

        String line = needs.describe();
        assertTrue(line.contains("food 8/20"), line);
        assertTrue(line.contains("hungry"), line);
        assertTrue(line.contains("company"), line);
        assertTrue(line.contains("content"), line);
    }

    @Test
    @DisplayName("an empty roster says so rather than printing nothing")
    void anEmptyRosterIsPrintable() {
        assertEquals("no needs", new Needs().describe());
    }

    @Test
    @DisplayName("a kind is canonical per key, so two mods cannot disagree about one")
    void kindsAreCanonical() {
        assertSame(NeedKind.register("hunger"), NeedKind.HUNGER);
        assertSame(NeedKind.byKey("company").orElseThrow(), NeedKind.COMPANY);
        assertTrue(NeedKind.all().contains(NeedKind.HUNGER));
        assertThrows(IllegalArgumentException.class, () -> NeedKind.register(" "));
    }
}
