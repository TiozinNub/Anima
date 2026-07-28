package dev.luizloyola.anima.mod.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.mod.AnimaMod;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Puts a Config button next to Anima in Mod Menu's list, opening {@link YaclConfigScreen} over
 * Anima's knob set; the screen is set-generic, so a consuming mod needs only its own entrypoint and
 * store.
 *
 * <p>Both halves are optional: Mod Menu is the only thing that loads this class, and YACL's absence
 * is caught by the {@link FabricLoader#isModLoaded} check below, which returns Mod Menu's own "no
 * screen" answer — hence no {@code dev.isxander} type is named here. The id checked is YACL's own
 * and identical on Fabric and NeoForge, so nothing depends on Connector's mod-alias table, unlike
 * the Cloth Config this replaced.
 *
 * <p>Mod Menu runs on Fabric and Quilt clients only, and a Fabric jar under Connector cannot hook
 * NeoForge's config-screen extension point: elsewhere {@code config/anima.json} and
 * {@code /anima config} are the whole interface.
 */
public final class AnimaModMenu implements ModMenuApi {

    private static final String YACL = "yet_another_config_lib_v3";

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded(YACL)) {
            return parent -> null; // Mod Menu's own default: no Config button
        }
        return parent -> YaclConfigScreen.create(parent, Config.store(), AnimaMod.CONFIG);
    }
}
