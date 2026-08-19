package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.need.Binding;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.brain.instinct.Drives;
import dev.luizloyola.anima.core.brain.instinct.NeedDrive;
import dev.luizloyola.anima.core.config.KnobSpec.Kind;
import org.junit.jupiter.api.Test;

/**
 * What a drive takes from the need it was declared on: its bid, the side gate that keeps a
 * two-sided need's ends apart, and the cost ceiling that used to be one shared curve every drive
 * was fed through.
 */
class NeedDriveTest {

    private final FakeContext ctx = new FakeContext();

    @Test
    void theBidIsTheGaugesOwnPressure() {
        ctx.percepts.metabolism.setFoodLevel(20);
        assertEquals(0.0, Drives.EAT.pressure(ctx), "a full bar asks for nothing");
        ctx.percepts.metabolism.setFoodLevel(8);
        assertEquals(0.6, Drives.EAT.pressure(ctx), 1e-9, "hunger's ramp, not a second formula");
        ctx.percepts.metabolism.setFoodLevel(0);
        assertEquals(1.0, Drives.EAT.pressure(ctx));
    }

    /**
     * The plateaus the one shared curve used to hard-code, now read off the level the body is at —
     * so an operator moves them in a config file instead of in a class.
     */
    @Test
    void theBudgetIsWhateverLevelTheBodyIsAt() {
        ctx.percepts.metabolism.setFoodLevel(12); // peckish
        assertEquals(15.0, Drives.EAT.costTolerance(ctx), "a short errand's worth");
        ctx.percepts.metabolism.setFoodLevel(6); // hungry
        assertEquals(60.0, Drives.EAT.costTolerance(ctx), "a modest journey to known food");
        ctx.percepts.metabolism.setFoodLevel(1); // starving
        assertTrue(Double.isInfinite(Drives.EAT.costTolerance(ctx)),
                "-1 in config is unbounded: pay any price");
    }

    @Test
    void aRootIsFreshEveryGrant() {
        var a = Drives.EAT.root(ctx);
        var b = Drives.EAT.root(ctx);
        assertInstanceOf(SatisfyHunger.class, a);
        assertNotSame(a, b, "each grant builds a new tree — never a cached instance");
    }

    /**
     * The cooldown key is the binding's, not the class's: every need drive is one class, so the
     * default would file every drive's fail-cooldown under a single shared entry.
     */
    @Test
    void theKeyNamesTheBindingRatherThanTheClass() {
        assertEquals("eat", Drives.EAT.key());
        assertEquals("eat", Drives.EAT.describe());
    }

    /**
     * A body without the gauge is not asking, and does not have to be asked about — which is what
     * lets one drive be portable across bodies that do not agree about what they feel.
     *
     * <p>Declared without levels on purpose: {@code ProfileAspect} freezes at the first species,
     * and a levelled need registers three aspects per level.
     */
    @Test
    void aNeedThisBodyDoesNotHaveNeverBids() {
        NeedKind absent = NeedKind.declare("test_absent", Kind.DOUBLE, 0.0, 1.0, "nothing")
                .drive(Binding.Side.BELOW, "test_absent_drive")
                .build();
        NeedDrive drive = new NeedDrive(absent.binding("test_absent_drive"), c -> new Idle(1));
        assertEquals(0.0, drive.pressure(ctx));
        assertEquals(0.0, drive.costTolerance(ctx), "and nothing to read means nothing to spend");
    }

    /**
     * Company presses at both ends, but only the lonely one drives (see {@code NeedKind.COMPANY} —
     * the crowded end modulates instead, {@code Comfort}'s job already). The side gate is what
     * stops the drive bidding once the body is on the other side of comfortable, or inside it. The
     * ABOVE side of this same gate is pinned separately, in {@code CompanyTest} — see the note
     * there on why it cannot be a second drive declared here.
     */
    @Test
    void aSideBoundDriveOnlyBidsThroughTheEndItAnswers() {
        NeedDrive seek = new NeedDrive(
                NeedKind.COMPANY.binding("seek_people"), c -> new Idle(1));

        ctx.percepts.company.setValue(0.05); // well below the comfortable stretch
        assertTrue(seek.pressure(ctx) > 0.0, "lonely -> the below-comfort drive bids");

        ctx.percepts.company.setValue(0.6); // content
        assertEquals(0.0, seek.pressure(ctx), "not from inside the band");

        ctx.percepts.company.setValue(0.98); // well above it
        assertEquals(0.0, seek.pressure(ctx), "nor from the crowded end, which is not its own");
    }

    @Test
    void aModulatorCannotBeADrive() {
        NeedKind weighing = NeedKind.declare("test_weighing", Kind.DOUBLE, 0.0, 1.0, "nothing")
                .modulate("test_weight")
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> new NeedDrive(weighing.binding("test_weight"), c -> new Idle(1)),
                "it weighs a decision; it does not make one");
    }
}
