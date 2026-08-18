package dev.luizloyola.anima.mod.webdebug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.mod.command.Replies;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /anima web-debugger} — the discovery path, the switch, and the door.
 *
 * <p><b>{@code start} runs it for this session and touches no setting.</b> That is the split worth
 * knowing: {@code web_debugger.enabled} is the AUTO-START switch, consulted once when a world
 * loads, and taking a look at a running world is not a decision about every future world. An
 * operator who wants it up every time sets the knob; an operator who wants it now types this.
 *
 * <p><b>{@code browser} is the only gated node under {@code /anima}</b>, which is otherwise
 * ungated to match {@code /autarkia}. Everything else here reads or drives agents, which is what
 * the root is for; this one hands a browser standing permission to do the same, and that is an
 * operator's decision even on a server where the rest is not.
 */
public final class WebCommands {

    private WebCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("web-debugger")
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("start").executes(ctx -> start(ctx.getSource())))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(browser());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> browser() {
        SuggestionProvider<CommandSourceStack> waiting = (ctx, builder) ->
                SharedSuggestionProvider.suggest(
                        WebDebugger.browsers().waiting().stream()
                                .map(WebBrowsers.Waiting::key).toList(), builder);
        SuggestionProvider<CommandSourceStack> accepted = (ctx, builder) ->
                SharedSuggestionProvider.suggest(WebDebugger.browsers().accepted(), builder);
        return Commands.literal("browser")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("open").executes(ctx -> open(ctx.getSource())))
                .then(Commands.literal("close").executes(ctx -> close(ctx.getSource())))
                .then(Commands.literal("accept")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(waiting)
                                .executes(ctx -> accept(ctx.getSource(), key(ctx)))))
                .then(Commands.literal("reject")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(waiting)
                                .executes(ctx -> reject(ctx.getSource(), key(ctx)))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(accepted)
                                .executes(ctx -> revoke(ctx.getSource(), key(ctx)))));
    }

    private static String key(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "key").toLowerCase(Locale.ROOT);
    }

    // --- the server -------------------------------------------------------------------------

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
     * The address as a clickable line. It carries no key — a browser opening it makes its own and
     * asks — so the line below saying how to answer that is the load-bearing half.
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
        if (WebDebugger.browsers().accepted().isEmpty()) {
            Replies.send(source, () -> Component.literal(
                    "  no browser is accepted yet — run browser open, then load the page")
                    .append(button(" [open]", "/anima web-debugger browser open",
                            "Let a new browser ask, for a minute"))
                    .withStyle(ChatFormatting.YELLOW));
        }
        // Said here as well as in the log: whoever is reading this is the person who can undo it,
        // and the log line scrolls past on a busy server.
        if (!WebDebugger.loopbackOnly()) {
            Replies.send(source, () -> Component.literal("  bound to " + WebDebugger.host()
                    + " — reachable off this machine, with no TLS. An accepted browser key is the "
                    + "only guard, and it crosses the wire in the clear.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    // --- the door ---------------------------------------------------------------------------

    private static int list(CommandSourceStack source) {
        WebBrowsers browsers = WebDebugger.browsers();
        List<WebBrowsers.Waiting> waiting = browsers.waiting();
        List<String> accepted = browsers.accepted();

        long open = browsers.openSecondsLeft();
        Replies.send(source, () -> Component.literal("Web debugger browsers — "
                + (open > 0 ? "open for " + open + "s" : "closed to new ones"))
                .withStyle(open > 0 ? ChatFormatting.YELLOW : ChatFormatting.AQUA));

        if (waiting.isEmpty()) {
            Replies.send(source, () -> Component.literal("  nobody is asking")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        for (WebBrowsers.Waiting browser : waiting) {
            Replies.send(source, () -> Component.literal("  " + browser.key())
                    .withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(" from " + browser.from() + ", asked "
                            + since(browser.askedAtMillis())).withStyle(ChatFormatting.DARK_GRAY))
                    .append(button(" [accept]",
                            "/anima web-debugger browser accept " + browser.key(),
                            "Let this browser read every mind, and drive them"))
                    .append(button(" [reject]",
                            "/anima web-debugger browser reject " + browser.key(),
                            "Drop it from the queue. It may ask again")));
        }
        if (accepted.isEmpty()) {
            Replies.send(source, () -> Component.literal("  none accepted")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        for (String key : accepted) {
            Replies.send(source, () -> Component.literal("  " + key)
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(" accepted").withStyle(ChatFormatting.DARK_GRAY))
                    .append(button(" [revoke]", "/anima web-debugger browser revoke " + key,
                            "Shut this browser out")));
        }
        return waiting.size() + accepted.size();
    }

    private static int open(CommandSourceStack source) {
        WebDebugger.browsers().open();
        // LOGGED: for the next minute anything on this machine can put itself in the queue, and
        // whoever else is administering the server should see that happen.
        Replies.send(source, () -> Component.literal("Open for " + WebBrowsers.OPEN_MILLIS / 1000
                + "s — load the page now. It shuts again as soon as one browser asks.")
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int close(CommandSourceStack source) {
        WebDebugger.browsers().close();
        Replies.send(source, () -> Component.literal("Shut. No new browser can ask.")
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private static int accept(CommandSourceStack source, String key) {
        WebBrowsers.Admission admission = WebDebugger.browsers().accept(key);
        switch (admission) {
            case MALFORMED -> {
                Replies.fail(source, Component.literal("\"" + key
                        + "\" is not a browser key — they read like two-words-joined."));
                return 0;
            }
            case ALREADY -> {
                Replies.send(source, () -> Component.literal(key + " was already accepted.")
                        .withStyle(ChatFormatting.GRAY));
                return 0;
            }
            default -> {
                // LOGGED: this is the grant. Nothing else in the world records that it happened.
                Replies.send(source, () -> Component.literal(key
                        + " may now read every mind here, and drive them.")
                        .withStyle(ChatFormatting.GREEN), true);
                return 1;
            }
        }
    }

    private static int reject(CommandSourceStack source, String key) {
        if (!WebDebugger.browsers().reject(key)) {
            Replies.send(source, () -> Component.literal(key + " was not asking.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.literal(key + " dropped from the queue.")
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private static int revoke(CommandSourceStack source, String key) {
        // Through WebDebugger, not the register: revoking has to close the stream that browser is
        // already holding, or it takes effect whenever the page next happens to reconnect.
        if (!WebDebugger.revoke(key)) {
            Replies.send(source, () -> Component.literal(key + " was not accepted.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        // LOGGED, for the reason accept is: it is the other half of the same decision.
        Replies.send(source, () -> Component.literal(key
                + " shut out, and its stream closed. It may ask again.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Tells every operator in the game that a browser is waiting. Called from the tick thread; see
     * {@code WebDebugger.announce} for why it exists at all.
     *
     * <p>Operators only, and that is the point of the line rather than a detail of it: the key
     * <em>is</em> the credential once accepted, so a broadcast would hand it to everybody in chat
     * and leave them waiting for somebody else to make it work.
     */
    static void tellOperators(MinecraftServer server, String key, String from) {
        Component line = Component.literal("A browser is asking to read this world: ")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(key).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + from + ")").withStyle(ChatFormatting.DARK_GRAY))
                .append(button(" [accept]", "/anima web-debugger browser accept " + key,
                        "Let it read every mind here, and drive them"))
                .append(button(" [reject]", "/anima web-debugger browser reject " + key,
                        "Drop it from the queue. It may ask again"));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Through a source stack rather than the player: the permission API moved in 26.1 and
            // this is the one spelling both targets share.
            if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)
                    .test(player.createCommandSourceStack())) {
                player.sendSystemMessage(line);
            }
        }
    }

    /** A clickable word that runs {@code command}, with {@code hover} explaining what it will do. */
    private static Component button(String label, String command, String hover) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** Coarse on purpose: an operator wants "just now" or "a while ago", not a duration. */
    private static String since(long millis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        return seconds < 3600 ? seconds / 60 + "m ago" : seconds / 3600 + "h ago";
    }
}
