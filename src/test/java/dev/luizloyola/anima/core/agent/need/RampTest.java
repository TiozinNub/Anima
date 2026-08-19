package dev.luizloyola.anima.core.agent.need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The migration proof. Hunger's pressure ({@code 1 - food/20} in the metabolism), its hard-coded
 * bands and the arbiter's cost curve were three copies of the same thresholds; Company's was a
 * comfort band and a distance formula by hand. All four are now one declaration and one
 * {@link Ramp}, and the old arithmetic is reproduced here literally, so an edit to a corner has to
 * argue with the number it changes.
 */
class RampTest {

    private static final double DELTA = 1e-9;
    private static final AgentProfile SETTLER = TestSpecies.PROFILE;

    @Test
    @DisplayName("hunger's ramp is still 1 - food/20, at every point on the bar")
    void hungerReproducesTheOldPressure() {
        Ramp ramp = NeedKind.HUNGER.ramp();
        for (int food = 0; food <= 20; food++) {
            assertEquals(1.0 - food / 20.0, ramp.pressureAt(SETTLER, food), DELTA,
                    "food " + food);
        }
    }

    @Test
    @DisplayName("hunger's levels are still the bands the metabolism used to carve")
    void hungerReproducesTheOldBands() {
        // Metabolism.band(), verbatim as it stood before it was deleted.
        Ramp ramp = NeedKind.HUNGER.ramp();
        for (int food = 0; food <= 20; food++) {
            String expected = food <= 3 ? "starving"
                    : food <= 8 ? "hungry"
                    : food <= 14 ? "peckish"
                    : "sated";
            assertEquals(expected, ramp.levelAt(SETTLER, food).key(), "food " + food);
        }
    }

    @Test
    @DisplayName("company's ramp is the old comfort band, with the lonely tail capped flat")
    void companyReproducesTheOldPressureExceptWhereItIsCapped() {
        // Company.pressure() as it stood before Ramp: 0 inside [0.35, 0.85], and outside it the
        // distance to the nearest edge over the room on that side. Still exact everywhere EXCEPT
        // below `lonely`, where the floor is now anchored (decision: Luiz, 2026-08-19) so wanting
        // company can never out-bid mind.preempt and interrupt work. The old curve climbed to 1.0
        // there; it now holds at what `lonely` declares.
        Ramp ramp = NeedKind.COMPANY.ramp();
        double low = 0.35;
        double high = 0.85;
        double lonely = 0.175;
        for (int step = 0; step <= 1000; step++) {
            double value = step / 1000.0;
            double old = value < low ? (low - value) / low
                    : value > high ? (value - high) / (1.0 - high)
                    : 0.0;
            double expected = value < lonely ? 0.50 : old;
            assertEquals(expected, ramp.pressureAt(SETTLER, value), 1e-9, "company " + value);
            // The cap only ever quietens it — it must not have raised the bid anywhere.
            assertTrue(ramp.pressureAt(SETTLER, value) <= old + 1e-9, "company " + value);
        }
    }

    @Test
    @DisplayName("a level owns up to and including its own value, moving away from comfort")
    void aLevelOwnsItsOwnBoundary() {
        Ramp hunger = NeedKind.HUNGER.ramp();
        // You keep the better name until you REACH the worse level's number: peckish starts at 14,
        // so 15 is not yet.
        assertEquals("sated", hunger.levelAt(SETTLER, 15).key());
        assertEquals("peckish", hunger.levelAt(SETTLER, 14).key());
        assertEquals("peckish", hunger.levelAt(SETTLER, 9).key());
        assertEquals("hungry", hunger.levelAt(SETTLER, 8).key());
    }

    @Test
    @DisplayName("wanting starts before the word for it does")
    void pressureLeavesZeroBeforeTheNameChanges() {
        // A settler at food 17 is still called sated and is already bidding a little: the number
        // glides while the name steps.
        Ramp hunger = NeedKind.HUNGER.ramp();
        assertEquals("sated", hunger.levelAt(SETTLER, 17).key());
        assertEquals(0.15, hunger.pressureAt(SETTLER, 17), DELTA);
        assertTrue(hunger.pressureAt(SETTLER, 17) > 0.0);
    }

    @Test
    @DisplayName("an end of the axis no level anchors is as urgent as the need gets")
    void unanchoredEndsPinAtFullPressure() {
        // Hunger anchors its ceiling with `sated` at a full bar and no pressure, so only its floor
        // pins — which is what makes the ramp reach 1.0 on an empty stomach rather than stopping at
        // `starving`'s 0.85. Company is the counter-example at BOTH ends now: its floor carries a
        // level (`desolate`), so it stops at what that declares, while its ceiling reaches 1.0
        // because `crowded` sits on the axis top and says so itself.
        assertEquals(0.0, NeedKind.HUNGER.ramp().pressureAt(SETTLER, 20), DELTA);
        assertEquals(1.0, NeedKind.HUNGER.ramp().pressureAt(SETTLER, 0), DELTA);
        assertEquals(0.50, NeedKind.COMPANY.ramp().pressureAt(SETTLER, 0.0), DELTA,
                "an anchored floor stops where its level says, not at full pressure");
        assertEquals(1.0, NeedKind.COMPANY.ramp().pressureAt(SETTLER, 1.0), DELTA);
    }

    @Test
    @DisplayName("a reading past either end of the axis is clamped, not extrapolated")
    void readingsOffTheAxisAreClamped() {
        Ramp hunger = NeedKind.HUNGER.ramp();
        assertEquals(hunger.pressureAt(SETTLER, 0), hunger.pressureAt(SETTLER, -5), DELTA);
        assertEquals(hunger.pressureAt(SETTLER, 20), hunger.pressureAt(SETTLER, 99), DELTA);
    }

    @Test
    @DisplayName("severity is derived from pressure, so it can never disagree with it")
    void severityFollowsPressure() {
        Ramp hunger = NeedKind.HUNGER.ramp();
        assertEquals(Severity.COMFORTABLE, Severity.of(hunger.pressureAt(SETTLER, 20)));
        assertEquals(Severity.MILD, Severity.of(hunger.pressureAt(SETTLER, 14)));
        assertEquals(Severity.URGENT, Severity.of(hunger.pressureAt(SETTLER, 8)));
        assertEquals(Severity.CRITICAL, Severity.of(hunger.pressureAt(SETTLER, 3)));
    }

    @Test
    @DisplayName("the drawable top is the highest knee, not the axis a need may declare")
    void topIsTheHighestKnee() {
        // An axis bounds what any body may DECLARE; the top knee is what THIS one reaches. Breath
        // declares to 1200 so a species may say how deep its lungs are, and a settler's hold 300 —
        // drawn on the axis, a full lungful would be a quarter of a bar that never moves again.
        assertEquals(300.0, NeedKind.BREATH.ramp().top(SETTLER), DELTA);
        assertEquals(1200.0, NeedKind.BREATH.axisMax(), DELTA);
        assertEquals(20.0, NeedKind.VIGOR.ramp().top(SETTLER), DELTA);
        assertEquals(1024.0, NeedKind.VIGOR.axisMax(), DELTA);
        // Hunger and company anchor their own ceilings, so for them the two agree.
        assertEquals(20.0, NeedKind.HUNGER.ramp().top(SETTLER), DELTA);
        assertEquals(1.0, NeedKind.COMPANY.ramp().top(SETTLER), DELTA);
    }

    @Test
    @DisplayName("a species that moves its top knee takes the drawable top with it")
    void topFollowsTheProfile() {
        // Read live and by value rather than by name, so this holds for a consumer that renamed
        // `healthy` as much as for one that only retuned it.
        AgentProfile tough = TestSpecies.with(
                NeedKind.VIGOR.level("healthy").orElseThrow().valueAspect(), 40.0);
        assertEquals(40.0, NeedKind.VIGOR.ramp().top(tough), DELTA);
    }

    @Test
    @DisplayName("the drawable floor is the axis, because under the lowest knee is where you die")
    void floorIsTheAxisMinimum() {
        // The mirror of `top` does NOT hold: hunger's lowest knee is `starving` at 3, and the 0..3
        // under it is both reachable and the stretch that matters.
        assertEquals(0.0, NeedKind.HUNGER.ramp().floor(), DELTA);
        assertEquals(0.0, NeedKind.VIGOR.ramp().floor(), DELTA);
        assertEquals(0.0, NeedKind.COMPANY.ramp().floor(), DELTA);
    }
}
