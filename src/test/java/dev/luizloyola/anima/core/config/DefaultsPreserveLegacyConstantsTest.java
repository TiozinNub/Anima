package dev.luizloyola.anima.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesKnobs;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.board.SiteClaims;
import dev.luizloyola.anima.core.brain.instinct.FleeInstinct;
import dev.luizloyola.anima.core.brain.instinct.WanderInstinct;
import dev.luizloyola.anima.core.brain.knowledge.CrescentSampler;
import dev.luizloyola.anima.core.brain.knowledge.PoiSensorCore;
import dev.luizloyola.anima.core.brain.knowledge.RegionGrowth;
import dev.luizloyola.anima.core.log.JournalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Read-through, proven at both tiers: <b>nothing caches</b>. A value installed after an organ took
 * its reference is visible to that organ.
 *
 * <p>The old assertion that Anima's defaults equalled the constants they replaced is gone — half
 * those numbers describe a body, and the mod shipping it declares them (Autarkia's
 * {@code PersonSpeciesTest}).
 */
class DefaultsPreserveLegacyConstantsTest {

    @AfterEach
    void restoreGlobalConfig() {
        Config.reset();
    }

    @Test
    @DisplayName("with no config installed, Anima's own limits read their documented defaults")
    void animaLimitsMatchTheirDefaults() {
        Config.reset();

        assertEquals(64, PoiSensorCore.readsPerTick(), "PoiSensorCore.READS_PER_TICK");
        assertEquals(1024, PoiSensorCore.queueCap(), "PoiSensorCore.QUEUE_CAP");
        // No longer the legacy 512: at that cap a Person standing INSIDE a stand of four touching
        // mega spruces remembered two of them — a scan cut short loses crowns, and a crownless
        // trunk is not a tree.
        assertEquals(4096, RegionGrowth.maxBlocks(), "RegionGrowth.MAX_BLOCKS");
        assertEquals(600, SiteClaims.ttlTicks(), "SiteClaims.TTL_TICKS");
        assertEquals(256, JournalService.defaultMaxEntriesPerPerson(),
                "JournalService.DEFAULT_MAX_ENTRIES_PER_PERSON");
        assertEquals(20L * 60 * 10, JournalService.defaultMaxAgeTicks(),
                "JournalService.DEFAULT_MAX_AGE_TICKS");
    }

    @Test
    @DisplayName("a reload retunes live — an organ that took a reference still sees the change")
    void installedLimitsAreVisibleAtTheCallSites() {
        Config.install(Config.SET.defaults()
                .with(Knob.READS_PER_TICK, 20.0)
                .with(Knob.CLAIM_TTL_TICKS, 1200.0));

        assertEquals(20, PoiSensorCore.readsPerTick());
        assertEquals(1200, SiteClaims.ttlTicks());
    }

    @Test
    @DisplayName("the same is true one tier down: a species' file retunes a body already walking")
    void installedAspectsAreVisibleAtTheCallSites() {
        SpeciesKnobs knobs = SpeciesKnobs.of(TestSpecies.BIPED);
        KnobSet set = KnobSet.of("testmod", "Test Mod", knobs.knobs());
        ConfigStore store = new ConfigStore(set);
        AgentProfile held = knobs.profile(store); // as an organ holds it, for the body's whole life

        assertEquals(16.0, FleeInstinct.range(held));
        assertEquals(12, CrescentSampler.radius(held));

        store.install(set.defaults()
                .with(knobs.knob(ProfileAspect.FLEE_RANGE), 30.0)
                .with(knobs.knob(ProfileAspect.PLACES_RADIUS), 20.0));

        assertEquals(30.0, FleeInstinct.range(held),
                "the same object must see the new file; caching it would strand the agent");
        assertEquals(20, CrescentSampler.radius(held));
    }

    @Test
    @DisplayName("a body's own numbers reach the instincts that read them")
    void aspectsReachTheirCallSites() {
        AgentProfile profile = TestSpecies.PROFILE;

        assertEquals(0.1, Arbiter.stickiness(profile));
        assertEquals(0.6, Arbiter.preempt(profile));
        assertEquals(16.0, FleeInstinct.range(profile));
        assertEquals(12.0, FleeInstinct.ramp(profile));
        assertEquals(0.15, WanderInstinct.idlePressure(profile));
        assertEquals(8, WanderInstinct.defaultRadius(profile));
        assertEquals(12, CrescentSampler.radius(profile));
        assertEquals(24, RegionGrowth.maxSpread(profile));
    }
}
