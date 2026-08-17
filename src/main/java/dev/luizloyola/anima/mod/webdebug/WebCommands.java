package dev.luizloyola.anima.mod.webdebug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.luizloyola.anima.mod.command.Replies;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * {@code /anima web-debugger} — the discovery path, and the switch.
 *
 * <p>It exists because the address carries this installation's key, so it cannot simply be written
 * down in a runbook: a tool nobody can find the address of is not a tool. Bare, it prints the link.
 *
 * <p><b>{@code start} runs it for this session and touches no setting.</b> That is the split worth
 * knowing: {@code web_debugger.enabled} is the AUTO-START switch, consulted once when a world
 * loads, and taking a look at a running world is not a decision about every future world. An
 * operator who wants it up every time sets the knob; an operator who wants it now types this.
 */
public final class WebCommands {

    private WebCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("web-debugger")
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("start").executes(ctx -> start(ctx.getSource())))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())));
    }

    private static int show(CommandSourceStack source) {
        if (!WebDebugger.running()) {
            Replies.send(source, () -> Component.literal(
                    "The web debugger is not running — /anima web-debugger start")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        link(source);
        return 1;
    }

    /** Starts it whatever {@code web_debugger.enabled} says, and restarts a running one. */
    private static int start(CommandSourceStack source) {
        String problem = WebDebugger.start(source.getServer());
        if (problem != null) {
            Replies.fail(source, Component.literal("The web debugger did not start — " + problem));
            return 0;
        }
        link(source);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        if (!WebDebugger.running()) {
            Replies.send(source, () -> Component.literal("The web debugger was not running.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        WebDebugger.stop();
        // LOGGED: it is a socket somebody may be looking at, and nothing else narrates the close.
        Replies.send(source, () -> Component.literal("Web debugger stopped.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * The address as a clickable line. The key is in the URL, so {@code openUrl} saves it from
     * having to survive a copy out of chat.
     */
    private static void link(CommandSourceStack source) {
        String address = WebDebugger.address();
        Replies.send(source, () -> Component.literal(address)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(address)))));
        Replies.send(source, () -> Component.literal("  the UI loads from " + WebDebugger.appUrl())
                .withStyle(ChatFormatting.DARK_GRAY));
        if (!WebDebugger.enabled()) {
            Replies.send(source, () -> Component.literal(
                    "  this session only — set web_debugger.enabled for it to start with a world")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        // Said here as well as in the log: whoever is reading this is the person who can undo it,
        // and the log line scrolls past on a busy server.
        if (!WebDebugger.loopbackOnly()) {
            Replies.send(source, () -> Component.literal("  bound to " + WebDebugger.host()
                    + " — reachable off this machine, with no TLS and no login. The key in that "
                    + "URL is the only guard.").withStyle(ChatFormatting.RED));
        }
    }
}
