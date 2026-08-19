package dev.luizloyola.anima.core.agent.need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.brain.instinct.NeedDrive;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.WanderStep;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The company gauge — the first need that owns its own number, and the first with a band rather
 * than a floor.
 *
 * <p>The test biped's declaration is round: centre 0.6, width 0.5 (so the band is
 * exactly {@code [0.35, 0.85]}), solitude 48000 ticks and proximity 24000, so one neighbour is
 * exactly twice the drain and the arithmetic below can be asserted rather than approximated.
 */
class CompanyTest {

    private static final double DELTA = 1e-9;
    private static final double SOLITUDE = 1.0 / 48_000;
    private static final double PROXIMITY = 1.0 / 24_000;
    private static final double PER_LINE = 1.0 / 30;

    private static Company fresh() {
        return new Company(() -> TestSpecies.PROFILE);
    }

    @Test
    @DisplayName("a fresh body starts content, at the centre of its own band")
    void seedsToTheBandCentre() {
        Company company = fresh();
        assertEquals(0.6, company.value(), DELTA, "the species' centre, not zero");
        assertEquals("content", company.level().key());
        assertEquals(0.0, company.pressure(), DELTA, "inside the band there is nothing to want");
    }

    @Test
    @DisplayName("the seed happens on the FIRST read, whichever read that is")
    void seedsBeforeAnyReadCanSeeZero() {
        // The band's centre is a species question a field initialiser cannot ask, so every accessor
        // must seed on first read — or a body is born desperately lonely.
        assertEquals("content", fresh().level().key());
        assertEquals(0.0, fresh().pressure(), DELTA);
        assertTrue(fresh().describe().contains("content"));
        Company ticked = fresh();
        ticked.tick();
        assertEquals(0.6 - SOLITUDE, ticked.value(), DELTA, "seeded, THEN drained by one tick");
    }

    @Test
    @DisplayName("solitude drains, a known neighbour more than makes up for it")
    void solitudeDrainsAndProximityFills() {
        Company alone = fresh();
        alone.tick();
        assertEquals(0.6 - SOLITUDE, alone.value(), DELTA);

        Company withOne = fresh();
        withOne.observe(1);
        withOne.tick();
        assertEquals(0.6 + PROXIMITY - SOLITUDE, withOne.value(), DELTA,
                "one neighbour fills at twice the drain, so the net is upward");

        Company inACrowd = fresh();
        inACrowd.observe(4);
        inACrowd.tick();
        assertEquals(0.6 + 4 * PROXIMITY - SOLITUDE, inACrowd.value(), DELTA,
                "every known person counts; nothing anywhere decides what a crowd is");
    }

    @Test
    @DisplayName("a conversation is paid for by the LINE, not by the second")
    void conversationIsPaidPerLine() {
        Company talking = fresh();
        talking.setValue(0.20);
        talking.conversed();
        assertEquals(0.20 + PER_LINE, talking.value(), DELTA, "one line, one step, at once");
        talking.conversed();
        talking.conversed();
        assertEquals(0.20 + 3 * PER_LINE, talking.value(), DELTA);
    }

    @Test
    @DisplayName("a slow talker is not better company than a brisk one")
    void timeSpentTalkingBuysNothing() {
        // A per-tick fill while an encounter was open paid a slow answer more than a brisk one, and
        // went on paying while a body waited out a timeout on somebody already gone.
        Company brisk = fresh();
        brisk.setValue(0.20);
        Company slow = fresh();
        slow.setValue(0.20);

        for (int i = 0; i < 3; i++) {
            brisk.conversed();
            brisk.tick();
        }
        for (int i = 0; i < 3; i++) {
            slow.conversed();
            for (int t = 0; t < 200; t++) {
                slow.tick();
            }
        }

        assertTrue(slow.value() < brisk.value(),
                "the same three lines, dragged out over ten seconds, must not be worth MORE — "
                        + "with nobody counted nearby, all the extra time does is drain");
    }

    @Test
    @DisplayName("sitting through a pause is still worth something — as proximity, which is what it is")
    void aPauseIsStillTimeSpentTogether() {
        Company together = fresh();
        together.setValue(0.20);
        together.observe(1);
        for (int t = 0; t < 200; t++) {
            together.tick();
        }
        assertEquals(0.20 + 200 * (PROXIMITY - SOLITUDE), together.value(), DELTA,
                "the pause paid the proximity rate, and only that");
    }

    @Test
    @DisplayName("the level never leaves 0..1, however long it is pushed")
    void clampsBothWays() {
        Company empty = fresh();
        empty.setValue(0.0);
        for (int i = 0; i < 100; i++) {
            empty.tick();
        }
        assertEquals(0.0, empty.value(), DELTA);

        Company full = fresh();
        full.setValue(1.0);
        full.observe(50);
        for (int i = 0; i < 100; i++) {
            full.tick();
        }
        assertEquals(1.0, full.value(), DELTA);
    }

    @Test
    @DisplayName("the band reads on both sides, and pressure rises away from it in both directions")
    void bandIsBidirectional() {
        Company company = fresh();

        // A level owns up TO and INCLUDING its own value, moving away from comfort: `alone` starts
        // at 0.35, `content` runs to 0.85. Neither edge presses — the ramp's corner there is zero.
        company.setValue(0.35);
        assertEquals("alone", company.level().key(), "the boundary belongs to the outer level");
        assertEquals(0.0, company.pressure(), DELTA, "but the edge itself asks for nothing");

        company.setValue(0.36);
        assertEquals("content", company.level().key(), "just inside is content");

        company.setValue(0.85);
        assertEquals("content", company.level().key(), "and the far edge is still content");
        assertEquals(0.0, company.pressure(), DELTA);

        company.setValue(0.25);
        assertEquals("alone", company.level().key());

        company.setValue(0.175);
        assertEquals("lonely", company.level().key(), "its own boundary belongs to it");
        assertEquals(0.5, company.pressure(), DELTA, "halfway from the low edge down to empty");

        company.setValue(0.0);
        assertEquals(1.0, company.pressure(), DELTA, "as lonely as this body gets");

        company.setValue(0.925);
        assertEquals("crowded", company.level().key());
        assertEquals(0.5, company.pressure(), DELTA, "halfway from the high edge up to full");

        company.setValue(1.0);
        assertEquals(1.0, company.pressure(), DELTA, "as crowded as this body gets");
    }

    @Test
    @DisplayName("empty and full are equally uncomfortable — which one number could never say")
    void bothEndsPressEqually() {
        Company lonely = fresh();
        lonely.setValue(0.0);
        Company crowded = fresh();
        crowded.setValue(1.0);
        assertEquals(lonely.pressure(), crowded.pressure(), DELTA);
        assertTrue(lonely.value() != crowded.value(),
                "and they are at opposite ends of the level, which is the whole point of two "
                        + "numbers rather than one");
    }

    @Test
    @DisplayName("a hermit and a socialite are the same mechanism at different centres")
    void theBandIsPerSpecies() {
        // A hermit is content on very little: its comfortable stretch starts near the floor.
        Company hermit = new Company(() -> speciesWith(0.05, 0.9));
        hermit.setValue(0.3);
        assertEquals("content", hermit.level().key(), "0.3 is plenty of company for a hermit");

        // A socialite is not content until nearly full.
        Company socialite = new Company(() -> speciesWith(0.8, 1.0));
        socialite.setValue(0.3);
        assertEquals("alone", socialite.level().key(), "and miserable for a socialite");
    }

    @Test
    @DisplayName("every number is read live, so retuning a species retunes bodies already alive")
    void readsAspectsThroughRatherThanCaching() {
        // The config doctrine applied to aspects: a snapshot taken in the constructor would leave a
        // settler walking around on the band it was born with.
        AgentProfile[] current = {speciesWith(0.35, 0.85)};
        Company company = new Company(() -> current[0]);
        company.setValue(0.3);
        assertEquals("alone", company.level().key());

        current[0] = speciesWith(0.2, 0.9);
        assertEquals("content", company.level().key(),
                "the same level, on a species that now finds it comfortable");
    }

    @Test
    @DisplayName("the gauge names itself, and the roster finds it by that name")
    void isARegisteredGauge() {
        Company company = fresh();
        assertSame(NeedKind.COMPANY, company.kind());
        Needs needs = new Needs().add(company);
        assertSame(company, needs.gauge(NeedKind.COMPANY).orElseThrow());
    }

    /**
     * The crowded end weighs where the wander goes; it does not propose an errand of its own. The
     * behaviour has always been {@code Comfort}'s — this asserts the DECLARATION says so, because a
     * binding that promises a drive is a promise somebody will eventually try to keep.
     */
    @Test
    void theCrowdedEndModulatesRatherThanDriving() {
        Binding crowded = NeedKind.COMPANY.bindings().stream()
                .filter(b -> b.key().equals("stray_away"))
                .findFirst()
                .orElseThrow();

        assertEquals(Binding.Verb.MODULATE, crowded.verb());
        assertThrows(IllegalArgumentException.class,
                () -> new NeedDrive(crowded, ctx -> new WanderStep(8)),
                "NeedDrive already refuses a modulator — the guard and the intent now agree");
    }

    /**
     * {@code Side.ABOVE}'s positive case, pinned independently of {@code stray_away}'s production
     * fate — nothing else in the suite drives a two-sided need's far end any more.
     *
     * <p><b>Not a synthetic {@code NeedKind}</b>, deliberately: {@code NeedLevel} registers a fresh
     * {@link ProfileAspect} per corner, and by the time any test method runs, some earlier test has
     * already built a species and {@link ProfileAspect#register} refuses anything declared after
     * that (confirmed — {@code NeedKind.declare(...).level(...)} from inside a test throws
     * {@code IllegalStateException: "...was registered after the first species was declared"}).
     * {@code aNeedThisBodyDoesNotHaveNeverBids} only gets away with a synthetic need because it
     * declares no levels at all.
     *
     * <p>So this reuses {@code COMPANY}'s own already-registered band — real numbers, real
     * {@link Ramp} — and hand-builds a second, throwaway {@link Binding} on it (legal: same
     * package). It is never added to {@code COMPANY.bindings()} and touches nothing the
     * {@code /anima needs} readout walks; it exists only to drive {@link NeedDrive#pressure} through
     * the ABOVE arm of {@link Binding.Side#pressing}, the one branch nothing else still exercises.
     */
    @Test
    void theAboveGateStillFiresForAGenuineAboveDrive() {
        Binding aboveGate = new Binding(NeedKind.COMPANY.key(), Binding.Verb.DRIVE,
                Binding.Side.ABOVE, "test_above_gate");
        aboveGate.attach(NeedKind.COMPANY);
        NeedDrive drive = new NeedDrive(aboveGate, ctx -> new WanderStep(8));
        FakeContext ctx = new FakeContext();

        ctx.percepts.company.setValue(0.05); // well below the comfortable stretch
        assertEquals(0.0, drive.pressure(ctx), "not from the below side");

        ctx.percepts.company.setValue(0.6); // content
        assertEquals(0.0, drive.pressure(ctx), "nor from inside the band");

        ctx.percepts.company.setValue(0.98); // well above it
        assertTrue(drive.pressure(ctx) > 0.0,
                "but the gate itself still fires for a genuine ABOVE drive");
    }

    /** The test biped with its company levels moved, and everything else left alone. */
    private static AgentProfile speciesWith(double aloneAt, double contentAt) {
        Map<ProfileAspect, Double> overrides = Map.of(
                level("alone").valueAspect(), aloneAt,
                level("content").valueAspect(), contentAt);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_company");
        for (ProfileAspect aspect : ProfileAspect.all()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }

    private static NeedLevel level(String key) {
        return NeedKind.COMPANY.levels().stream().filter(l -> l.key().equals(key))
                .findFirst().orElseThrow();
    }
}
