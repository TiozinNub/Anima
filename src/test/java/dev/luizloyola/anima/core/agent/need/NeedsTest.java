package dev.luizloyola.anima.core.agent.need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.TestSpecies;
import java.util.ArrayList;
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

    /**
     * A settler already alive, company staged at {@code value} — one tick seeds the crossing
     * baseline (see {@link Needs#tick()}), so a caller's own tick afterwards can be asserted on
     * without also asserting the seed itself fired.
     */
    private static Needs needsWithCompany(double value) {
        Needs needs = settler(new Metabolism());
        setCompany(needs, value);
        needs.tick();
        return needs;
    }

    private static void setCompany(Needs needs, double value) {
        needs.gauge(NeedKind.COMPANY, Company.class).orElseThrow().setValue(value);
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

    @Test
    @DisplayName("a body's first tick seeds the baseline rather than announcing its birth level")
    void firstTickNeverFiresACrossing() {
        List<Needs.Crossing> seen = new ArrayList<>();
        Needs needs = settler(new Metabolism());
        setCompany(needs, 0.30); // "alone" from the moment it exists — nothing has ticked yet
        needs.onCrossing(seen::add);

        needs.tick(); // this body's very first tick, for every gauge it has

        assertTrue(seen.isEmpty(),
                "a body must not announce a crossing into the level it was born at");
    }

    @Test
    @DisplayName("a gauge crossing a declared level boundary fires once")
    void crossingALevelBoundaryFiresOnce() {
        List<Needs.Crossing> seen = new ArrayList<>();
        Needs needs = needsWithCompany(0.60);   // "content"
        needs.onCrossing(seen::add);

        setCompany(needs, 0.30);                 // into "alone"
        needs.tick();

        assertEquals(1, seen.size());
        assertEquals("content", seen.get(0).from().key());
        assertEquals("alone", seen.get(0).to().key());
    }

    @Test
    @DisplayName("jitter inside one band is silent")
    void jitterInsideOneBandIsSilent() {
        List<Needs.Crossing> seen = new ArrayList<>();
        Needs needs = needsWithCompany(0.50);    // "content"
        needs.onCrossing(seen::add);

        for (double v : new double[] {0.52, 0.48, 0.55, 0.41, 0.60}) {
            setCompany(needs, v);
            needs.tick();
        }

        assertTrue(seen.isEmpty(), "a gauge wandering inside one band has not crossed anything");
    }
}
