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
                        // The whole agent-shaped surface, for a world running the library on its
                        // own — and mounted again by each consumer under its own root, so nobody
                        // relearns a command they already type.
                        .then(AgentCommands.list())
                        // The only readout here that answers when there is no body left to ask.
                        .then(AgentCommands.grave())
                        .then(AgentCommands.select())
                        .then(AgentCommands.contacts())
                        .then(AgentCommands.party())
                        .then(AgentCommands.nav())
                        // A standing order, instead of typing nav goto at the body every 20 blocks.
                        .then(AgentCommands.follow())
                        .then(AgentCommands.probe())
                        // The craftbook lens — read-only.
                        .then(AgentCommands.recipes())
                        .then(AgentCommands.brain())
                        .then(AgentCommands.think())
                        .then(AgentCommands.log())
                        .then(AgentCommands.knowledge())
                        .then(AgentCommands.horizon())
                        .then(AgentCommands.survey())
                        .then(AgentCommands.claims())
                        .then(AgentCommands.peers())
                        // Every gauge the body declared — hunger and company today.
                        .then(AgentCommands.needs())
                        // What this one is running: species -> modifiers -> effective.
                        .then(AgentCommands.profile())
                        .then(AgentCommands.debug())
                        .then(AgentCommands.inv(registry))
                        .then(ConfigCommands.tree(Config.store(), configFile))));
    }
}
