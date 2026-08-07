package dev.luizloyola.anima.core.agent.need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
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
        assertEquals(0.6, company.level(), DELTA, "the species' centre, not zero");
        assertEquals(Company.Band.CONTENT, company.band());
        assertEquals(0.0, company.pressure(), DELTA, "inside the band there is nothing to want");
    }

    @Test
    @DisplayName("the seed happens on the FIRST read, whichever read that is")
    void seedsBeforeAnyReadCanSeeZero() {
        // The band's centre is a species question a field initialiser cannot ask, so every accessor
        // must seed on first read — or a body is born desperately lonely.
        assertEquals(Company.Band.CONTENT, fresh().band());
        assertEquals(0.0, fresh().pressure(), DELTA);
        assertTrue(fresh().describe().contains("content"));
        Company ticked = fresh();
        ticked.tick();
        assertEquals(0.6 - SOLITUDE, ticked.level(), DELTA, "seeded, THEN drained by one tick");
    }

    @Test
    @DisplayName("solitude drains, a known neighbour more than makes up for it")
    void solitudeDrainsAndProximityFills() {
        Company alone = fresh();
        alone.tick();
        assertEquals(0.6 - SOLITUDE, alone.level(), DELTA);

        Company withOne = fresh();
        withOne.observe(1);
        withOne.tick();
        assertEquals(0.6 + PROXIMITY - SOLITUDE, withOne.level(), DELTA,
                "one neighbour fills at twice the drain, so the net is upward");

        Company inACrowd = fresh();
        inACrowd.observe(4);
        inACrowd.tick();
        assertEquals(0.6 + 4 * PROXIMITY - SOLITUDE, inACrowd.level(), DELTA,
                "every known person counts; nothing anywhere decides what a crowd is");
    }

    @Test
    @DisplayName("a conversation is paid for by the LINE, not by the second")
    void conversationIsPaidPerLine() {
        Company talking = fresh();
        talking.setLevel(0.20);
        talking.conversed();
        assertEquals(0.20 + PER_LINE, talking.level(), DELTA, "one line, one step, at once");
        talking.conversed();
        talking.conversed();
        assertEquals(0.20 + 3 * PER_LINE, talking.level(), DELTA);
    }

    @Test
    @DisplayName("a slow talker is not better company than a brisk one")
    void timeSpentTalkingBuysNothing() {
        // A per-tick fill while an encounter was open paid a slow answer more than a brisk one, and
        // went on paying while a body waited out a timeout on somebody already gone.
        Company brisk = fresh();
        brisk.setLevel(0.20);
        Company slow = fresh();
        slow.setLevel(0.20);

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

        assertTrue(slow.level() < brisk.level(),
                "the same three lines, dragged out over ten seconds, must not be worth MORE — "
                        + "with nobody counted nearby, all the extra time does is drain");
    }

    @Test
    @DisplayName("sitting through a pause is still worth something — as proximity, which is what it is")
    void aPauseIsStillTimeSpentTogether() {
        Company together = fresh();
        together.setLevel(0.20);
        together.observe(1);
        for (int t = 0; t < 200; t++) {
            together.tick();
        }
        assertEquals(0.20 + 200 * (PROXIMITY - SOLITUDE), together.level(), DELTA,
                "the pause paid the proximity rate, and only that");
    }

    @Test
    @DisplayName("the level never leaves 0..1, however long it is pushed")
    void clampsBothWays() {
        Company empty = fresh();
        empty.setLevel(0.0);
        for (int i = 0; i < 100; i++) {
            empty.tick();
        }
        assertEquals(0.0, empty.level(), DELTA);

        Company full = fresh();
        full.setLevel(1.0);
        full.observe(50);
        for (int i = 0; i < 100; i++) {
            full.tick();
        }
        assertEquals(1.0, full.level(), DELTA);
    }

    @Test
    @DisplayName("the band reads on both sides, and pressure rises away from it in both directions")
    void bandIsBidirectional() {
        Company company = fresh();

        company.setLevel(0.35);
        assertEquals(Company.Band.CONTENT, company.band(), "the low edge is inside the band");
        assertEquals(0.0, company.pressure(), DELTA);

        company.setLevel(0.85);
        assertEquals(Company.Band.CONTENT, company.band(), "and so is the high edge");
        assertEquals(0.0, company.pressure(), DELTA);

        company.setLevel(0.175);
        assertEquals(Company.Band.LONELY, company.band());
        assertEquals(0.5, company.pressure(), DELTA, "halfway from the low edge down to empty");

        company.setLevel(0.0);
        assertEquals(1.0, company.pressure(), DELTA, "as lonely as this body gets");

        company.setLevel(0.925);
        assertEquals(Company.Band.CROWDED, company.band());
        assertEquals(0.5, company.pressure(), DELTA, "halfway from the high edge up to full");

        company.setLevel(1.0);
        assertEquals(1.0, company.pressure(), DELTA, "as crowded as this body gets");
    }

    @Test
    @DisplayName("empty and full are equally uncomfortable — which one number could never say")
    void bothEndsPressEqually() {
        Company lonely = fresh();
        lonely.setLevel(0.0);
        Company crowded = fresh();
        crowded.setLevel(1.0);
        assertEquals(lonely.pressure(), crowded.pressure(), DELTA);
        assertTrue(lonely.level() != crowded.level(),
                "and they are at opposite ends of the level, which is the whole point of two "
                        + "numbers rather than one");
    }

    @Test
    @DisplayName("a hermit and a socialite are the same mechanism at different centres")
    void theBandIsPerSpecies() {
        Company hermit = new Company(() -> speciesWith(0.15, 0.3));
        hermit.setLevel(0.3);
        assertEquals(Company.Band.CONTENT, hermit.band(), "0.3 is plenty of company for a hermit");

        Company socialite = new Company(() -> speciesWith(0.9, 0.2));
        socialite.setLevel(0.3);
        assertEquals(Company.Band.LONELY, socialite.band(), "and miserable for a socialite");
    }

    @Test
    @DisplayName("every number is read live, so retuning a species retunes bodies already alive")
    void readsAspectsThroughRatherThanCaching() {
        // The config doctrine applied to aspects: a snapshot taken in the constructor would leave a
        // settler walking around on the band it was born with.
        AgentProfile[] current = {speciesWith(0.6, 0.5)};
        Company company = new Company(() -> current[0]);
        company.setLevel(0.3);
        assertEquals(Company.Band.LONELY, company.band());

        current[0] = speciesWith(0.25, 0.3);
        assertEquals(Company.Band.CONTENT, company.band(),
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

    /** The test biped with a different company band and everything else left alone. */
    private static AgentProfile speciesWith(double centre, double width) {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.SOCIAL_COMPANY_CENTER, centre,
                ProfileAspect.SOCIAL_COMPANY_WIDTH, width);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_company");
        for (ProfileAspect aspect : ProfileAspect.all()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }
}
