package dev.luizloyola.anima.mod;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.mod.command.AnimaCommands;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.brain.RayPools;
import dev.luizloyola.anima.mod.config.ConfigFile;
import dev.luizloyola.anima.mod.config.DangerSection;
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

    /**
     * Anima's own {@code config/anima.json}. A consuming mod builds its own for its own knob
     * set — this one is not shared, and neither is the file.
     */
    public static final ConfigFile CONFIG = new ConfigFile(Config.store(), new DangerSection());

    @Override
    public void onInitialize() {
        // Read the file before anything can tune itself off a default. Problems are reported
        // rather than fatal: a hand-edited file degrades to the nearest legal configuration.
        for (String problem : CONFIG.reload()) {
            LOGGER.warn("config: {}", problem);
        }
        AgentBodies.install();
        RayPools.install();
        AnimaItems.init();
        AnimaCommands.register(CONFIG);
        LOGGER.info("Anima loaded — the machinery is ready for whoever wants a mind.");
    }
}
