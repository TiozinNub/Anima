package dev.luizloyola.anima.mod.command;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.mod.config.ConfigFile;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;

/**
 * The {@code /anima} command root — the library's own operator surface.
 *
 * <p>It exists because Anima's tunables are Anima's: reaching them through whichever NPC mod
 * happens to be installed would make the library unusable on its own. Anything about the
 * machinery rather than about somebody's creatures belongs here.
 */
public final class AnimaCommands {

    private AnimaCommands() {
    }

    /**
     * Registers {@code /anima …}. Ungated, matching {@code /autarkia} — dev/operator surfaces on
     * a single-player or trusted server, where gating one root and not its sibling would mislead.
     */
    public static void register(ConfigFile configFile) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("anima")
                        .then(ConfigCommands.tree(Config.store(), configFile))));
    }
}
