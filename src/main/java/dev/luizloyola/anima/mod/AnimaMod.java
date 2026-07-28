package dev.luizloyola.anima.mod;

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

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("Anima loaded — no souls yet, just the machinery.");
    }
}
