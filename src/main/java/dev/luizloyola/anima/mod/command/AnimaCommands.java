package dev.luizloyola.anima.mod.command;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.mod.config.ConfigFile;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;

import java.util.List;

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
     * Registers {@code /anima …}. <b>Op-gated whole, matching {@code /autarkia}</b> — every node
     * under it either drives an agent, exposes the machinery, or edits the config, so there is
     * none an ordinary player wants and several a shared server should not hand out. Brigadier
     * drops a failing root from the tree, so a non-op does not see it at all.
     */
    public static void register(ConfigFile configFile) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(CommandSurface.mount(
                        Commands.literal("anima")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)),
                        // hasSubject — mounted at the root AND under `as <person>`. A node
                        // belongs here if and only if its behaviour depends on WHICH agent it is
                        // about.
                        List.of(AgentCommands::select, AgentCommands::contacts,
                                AgentCommands::party, AgentCommands::places, AgentCommands::nav,
                                AgentCommands::follow, AgentCommands::brain, AgentCommands::think,
                                AgentCommands::log, AgentCommands::knowledge,
                                AgentCommands::horizon, AgentCommands::survey,
                                AgentCommands::claims, AgentCommands::peers, AgentCommands::needs,
                                AgentCommands::profile, AgentCommands::grave,
                                () -> AgentCommands.inv(registry),
                                () -> AgentCommands.store(registry)),
                        // noSubject — the root alone. `debug` is here on purpose: its layers are a
                        // per-player switch drawn over the SELECTION, so nothing about it varies
                        // with a subject and `as Cleo debug horizon true` would read like a promise
                        // it does not keep.
                        List.of(AgentCommands::list, AgentCommands::probe, AgentCommands::recipes,
                                AgentCommands::debug,
                                dev.luizloyola.anima.mod.webdebug.WebCommands::tree,
                                () -> ConfigCommands.tree(Config.store(), configFile)),
                        List.of())));
    }
}
