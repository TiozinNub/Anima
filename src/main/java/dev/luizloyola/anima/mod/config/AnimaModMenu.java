package dev.luizloyola.anima.mod.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.mod.AnimaMod;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Puts a Config button next to Anima in Mod Menu's list, opening {@link YaclConfigScreen} over
 * Anima's own knob set. The screen is set-generic: a consuming mod registers its own entrypoint
 * over its own store.
 *
 * <p>Both dependencies are optional. Mod Menu is the only thing that ever loads this class, through
 * the {@code modmenu} entrypoint, and YACL's absence is caught by {@link FabricLoader#isModLoaded}
 * below — which is why no {@code dev.isxander} type is named here.
 *
 * <p>The YACL id is the same on Fabric and NeoForge, so nothing depends on Sinytra Connector's
 * mod-alias table, unlike the Cloth Config this replaced (reachable only as
 * {@code cloth-config2}).
 *
 * <p>Mod Menu runs only on Fabric and Quilt clients, and a Fabric jar under Connector cannot hook
 * NeoForge's own config-screen extension point — so on a dedicated server, and for NeoForge users,
 * {@code config/anima.toml} and {@code /anima config} are the whole interface.
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
