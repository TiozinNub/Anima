package dev.luizloyola.anima.mod;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.mod.command.AnimaCommands;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.brain.RayPools;
import dev.luizloyola.anima.mod.brain.ReadPools;
import dev.luizloyola.anima.mod.brain.PlaceIndexes;
import dev.luizloyola.anima.mod.brain.RegionCaches;
import dev.luizloyola.anima.mod.config.ConfigFile;
import dev.luizloyola.anima.mod.identity.AnimaRecords;
import dev.luizloyola.anima.mod.store.StoreGuard;
import dev.luizloyola.anima.mod.item.AnimaItems;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anima's Fabric entrypoint. A <em>library mod</em>: it registers the machinery an autonomous body
 * needs to think (brain, navigation, perception, journal, config) and by itself puts nothing in the
 * world. Nothing here may name a Person — Autarkia's is the first consumer, a wolf could be the
 * second. See {@code docs/superpowers/specs/2026-07-27-anima-split-design.md}.
 */
public final class AnimaMod implements ModInitializer {
    public static final String MOD_ID = "anima";

    /** The library's log channel, shared by everything under {@code dev.luizloyola.anima}. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Anima's own {@code config/anima.toml}; a consuming mod builds its own for its own knob set. */
    public static final ConfigFile CONFIG = new ConfigFile(Config.store());

    @Override
    public void onInitialize() {
        // Read the file before anything can tune itself off a default. Problems are reported
        // rather than fatal: a hand-edited file degrades to the nearest legal configuration.
        for (String problem : CONFIG.reload()) {
            LOGGER.warn("config: {}", problem);
        }
        AgentBodies.install();
        RayPools.install();
        ReadPools.install();
        RegionCaches.install();
        PlaceIndexes.install();
        AnimaItems.init();
        AnimaRecords.install();
        // The one block Anima itself perceives: CraftFor must find tables, so the kind cannot
        // belong to any consumer.
        dev.luizloyola.anima.compat.craft.WorkbenchBlocks.register();
        // Teaches the plan-codec registry Anima's own tasks, before anything can load a plan.
        dev.luizloyola.anima.mod.brain.AnimaTasks.install();
        // As the server finishes starting: refuses to run a world whose memory did not load,
        // which vanilla swallows and then overwrites.
        StoreGuard.install();
        // Registered here so a bare install, or a consumer that paints nothing, still has it.
        dev.luizloyola.anima.mod.debug.CellOverlays.init();
        // The follow-me leash's tick. Here rather than in a consumer: `/anima follow` is on the
        // library's own root, so the order must be drivable with Anima alone.
        dev.luizloyola.anima.mod.nav.Escorts.init();
        // The hail channel. Anima's, not a consumer's: the Voice port and `/anima brain hail` are
        // on this root, so a bare install must be able to call out. See BeingHails.
        dev.luizloyola.anima.mod.brain.BeingHails.init();
        // The browser debug dashboard. Registers its lifecycle hooks only — it listens on nothing
        // until web_debugger.enabled says so.
        dev.luizloyola.anima.mod.webdebug.WebDebugger.install();
        AnimaCommands.register(CONFIG);
        LOGGER.info("Anima loaded — the machinery is ready for whoever wants a mind.");
    }
}
