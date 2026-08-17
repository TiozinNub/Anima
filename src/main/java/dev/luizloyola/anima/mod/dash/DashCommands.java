package dev.luizloyola.anima.mod.dash;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.mod.command.Replies;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * {@code /anima dash} — the dashboard's discovery path.
 *
 * <p>It exists because the address carries a token regenerated on every start, so the URL cannot
 * be written down anywhere: a tool nobody can find the address of is not a tool. Bare, it prints
 * the link; {@code on}/{@code off} start and stop it without editing the config file.
 *
 * <p>Starting from here also writes {@code dash.enabled}, so a dashboard switched on mid-session
 * comes back with the next world rather than surprising the operator by not doing.
 */
public final class DashCommands {

    private DashCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("dash")
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false)));
    }

    private static int show(CommandSourceStack source) {
        if (!DashServer.running()) {
            Replies.send(source, () -> Component.literal(
                    "The dashboard is off — /anima dash on").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        link(source);
        return 1;
    }

    private static int set(CommandSourceStack source, boolean on) {
        Config.install(Config.get().with(Knob.DASH_ENABLED, on ? 1.0 : 0.0));
        if (!on) {
            DashServer.stop();
            Replies.send(source, () -> Component.literal("Dashboard stopped.")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        String problem = DashServer.start(source.getServer());
        if (problem != null) {
            Replies.fail(source, Component.literal("Dashboard did not start — " + problem));
            return 0;
        }
        link(source);
        return 1;
    }

    /**
     * The address as a clickable line. The token is in the URL, so this is the one place it is
     * ever shown — and {@code openUrl} means it does not have to survive a copy out of chat.
     */
    private static void link(CommandSourceStack source) {
        String address = DashServer.address();
        Replies.send(source, () -> Component.literal(address)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(address)))));
        Replies.send(source, () -> Component.literal("  the UI loads from " + DashServer.appUrl())
                .withStyle(ChatFormatting.DARK_GRAY));
        // Said here as well as in the log: whoever is reading this is the person who can undo it,
        // and the log line scrolls past on a busy server.
        if (!DashServer.loopbackOnly()) {
            Replies.send(source, () -> Component.literal("  bound to " + DashServer.host()
                    + " — reachable off this machine, with no TLS and no login. The token in that "
                    + "URL is the only guard.").withStyle(ChatFormatting.RED));
        }
    }
}
