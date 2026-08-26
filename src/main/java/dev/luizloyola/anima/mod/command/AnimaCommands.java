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
                        // hasSubject — mounted at the root AND under `as <person>`. One entry for
                        // now: the seam is proved end to end on the smallest surface it can be,
                        // before the rest of the tree is hung off it.
                        List.of(AgentCommands::log),
                        // noSubject — the root alone.
                        List.of(AgentCommands::list, AgentCommands::grave, AgentCommands::select,
                                AgentCommands::contacts, AgentCommands::party,
                                AgentCommands::places, AgentCommands::nav, AgentCommands::follow,
                                AgentCommands::probe, AgentCommands::recipes,
                                AgentCommands::brain, AgentCommands::think,
                                AgentCommands::knowledge, AgentCommands::horizon,
                                AgentCommands::survey, AgentCommands::claims,
                                AgentCommands::peers, AgentCommands::needs,
                                AgentCommands::profile, AgentCommands::debug,
                                dev.luizloyola.anima.mod.webdebug.WebCommands::tree,
                                () -> AgentCommands.inv(registry),
                                () -> AgentCommands.store(registry),
                                () -> ConfigCommands.tree(Config.store(), configFile)),
                        List.of())));
    }
}
