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
            Replies.send(source, () -> Component.translatable("anima.webdebug.not_running")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        link(source);
        return 1;
    }

    /**
     * Starts it whatever {@code web_debugger.enabled} says, and restarts a running one.
     *
     * <p><b>Opens the door on the way</b>, which auto-start deliberately does not. Typing this is a
     * person saying they are about to look at the thing, and making them type a second command
     * first would be ceremony; a world booting with {@code enabled} set is nobody saying anything,
     * and a door left open on an unattended server is the case the door exists for.
     */
    private static int start(CommandSourceStack source) {
        String problem = WebDebugger.start(source.getServer());
        if (problem != null) {
            // The problem itself is the socket's own complaint, and arrives in one language.
            Replies.fail(source, Component.translatable("anima.webdebug.start_failed", problem));
            return 0;
        }
        // After the start, not before: it restarts a running server, and stopping clears the door.
        WebDebugger.browsers().open();
        link(source);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        if (!WebDebugger.running()) {
            Replies.send(source, () -> Component.translatable("anima.webdebug.was_not_running")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        WebDebugger.stop();
        // LOGGED: it is a socket somebody may be looking at, and nothing else narrates the close.
        Replies.send(source, () -> Component.translatable("anima.webdebug.stopped")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * The address as a clickable line, named so it does not read as a stray URL. It carries no key
     * — a browser opening it makes its own and asks — so the line below saying how to answer that
     * is the load-bearing half.
     */
    private static void link(CommandSourceStack source) {
        String address = WebDebugger.address();
        // The address goes in as a component, not as text: the label is translated around it while
        // the click event and the link colouring stay on the URL alone.
        Replies.send(source, () -> Component.translatable("anima.webdebug.at",
                        Component.literal(address).withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(
                                        java.net.URI.create(address)))))
                .withStyle(ChatFormatting.GRAY));
        Replies.send(source, () -> indent(Component.translatable("anima.webdebug.app_url",
                WebDebugger.appUrl()).withStyle(ChatFormatting.DARK_GRAY)));
        if (!WebDebugger.enabled()) {
            Replies.send(source, () -> indent(Component.translatable(
                    "anima.webdebug.session_only").withStyle(ChatFormatting.DARK_GRAY)));
        }
        long open = WebDebugger.browsers().openSecondsLeft();
        if (open > 0) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.open_for",
                    open).withStyle(ChatFormatting.YELLOW)));
        } else if (WebDebugger.browsers().accepted().isEmpty()) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.none_accepted")
                    .append(button(" [open]", "/anima web-debugger browser open",
                            "anima.webdebug.hover.open"))
                    .withStyle(ChatFormatting.YELLOW)));
        }
        // Said here as well as in the log: whoever is reading this is the person who can undo it,
        // and the log line scrolls past on a busy server.
        if (!WebDebugger.loopbackOnly()) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.exposed",
                    WebDebugger.host()).withStyle(ChatFormatting.RED)));
        }
    }

    // --- the door ---------------------------------------------------------------------------

    private static int list(CommandSourceStack source) {
        WebBrowsers browsers = WebDebugger.browsers();
        List<WebBrowsers.Waiting> waiting = browsers.waiting();
        List<String> accepted = browsers.accepted();

        long open = browsers.openSecondsLeft();
        Replies.send(source, () -> (open > 0
                        ? Component.translatable("anima.webdebug.list_open", open)
                        : Component.translatable("anima.webdebug.list_closed"))
                .withStyle(open > 0 ? ChatFormatting.YELLOW : ChatFormatting.AQUA));

        if (waiting.isEmpty()) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.nobody_asking")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }
        for (WebBrowsers.Waiting browser : waiting) {
            Replies.send(source, () -> indent(Component.literal(browser.key())
                    .withStyle(ChatFormatting.WHITE)
                    .append(Component.translatable("anima.webdebug.asked_from", browser.from(),
                            since(browser.askedAtMillis())).withStyle(ChatFormatting.DARK_GRAY))
                    .append(button(" [accept]",
                            "/anima web-debugger browser accept " + browser.key(),
                            "anima.webdebug.hover.accept"))
                    .append(button(" [reject]",
                            "/anima web-debugger browser reject " + browser.key(),
                            "anima.webdebug.hover.reject"))));
        }
        if (accepted.isEmpty()) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.none_yet")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }
        for (String key : accepted) {
            Replies.send(source, () -> indent(Component.literal(key)
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.translatable("anima.webdebug.is_accepted")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(button(" [revoke]", "/anima web-debugger browser revoke " + key,
                            "anima.webdebug.hover.revoke"))));
        }
        return waiting.size() + accepted.size();
    }

    private static int open(CommandSourceStack source) {
        WebDebugger.browsers().open();
        // LOGGED: for the next minute anything on this machine can put itself in the queue, and
        // whoever else is administering the server should see that happen.
        Replies.send(source, () -> Component.translatable("anima.webdebug.opened",
                WebBrowsers.OPEN_MILLIS / 1000).withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int close(CommandSourceStack source) {
        WebDebugger.browsers().close();
        Replies.send(source, () -> Component.translatable("anima.webdebug.closed")
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private static int accept(CommandSourceStack source, String key) {
        WebBrowsers.Admission admission = WebDebugger.browsers().accept(key);
        switch (admission) {
            case MALFORMED -> {
                Replies.fail(source, Component.translatable("anima.webdebug.malformed_key", key));
                return 0;
            }
            case ALREADY -> {
                Replies.send(source, () -> Component.translatable("anima.webdebug.already", key)
                        .withStyle(ChatFormatting.GRAY));
                return 0;
            }
            default -> {
                // LOGGED: this is the grant. Nothing else in the world records that it happened.
                Replies.send(source, () -> Component.translatable("anima.webdebug.accepted", key)
                        .withStyle(ChatFormatting.GREEN), true);
                return 1;
            }
        }
    }

    private static int reject(CommandSourceStack source, String key) {
        if (!WebDebugger.browsers().reject(key)) {
            Replies.send(source, () -> Component.translatable("anima.webdebug.not_asking", key)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("anima.webdebug.rejected", key)
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private static int revoke(CommandSourceStack source, String key) {
        // Through WebDebugger, not the register: revoking has to close the stream that browser is
        // already holding, or it takes effect whenever the page next happens to reconnect.
        if (!WebDebugger.revoke(key)) {
            Replies.send(source, () -> Component.translatable("anima.webdebug.not_accepted", key)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        // LOGGED, for the reason accept is: it is the other half of the same decision.
        Replies.send(source, () -> Component.translatable("anima.webdebug.revoked", key)
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
        Component line = Component.translatable("anima.webdebug.asking")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(key).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + from + ")").withStyle(ChatFormatting.DARK_GRAY))
                .append(button(" [accept]", "/anima web-debugger browser accept " + key,
                        "anima.webdebug.hover.accept_this"))
                .append(button(" [reject]", "/anima web-debugger browser reject " + key,
                        "anima.webdebug.hover.reject"));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Through a source stack rather than the player: the permission API moved in 26.1 and
            // this is the one spelling both targets share.
            if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)
                    .test(player.createCommandSourceStack())) {
                player.sendSystemMessage(line);
            }
        }
    }

    /**
     * A clickable word that runs {@code command}, with {@code hoverKey} explaining what it will do.
     *
     * <p>The label stays literal: these are bracketed verbs standing in for the command underneath,
     * and the command is not translated either.
     */
    private static Component button(String label, String command, String hoverKey) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(hoverKey))));
    }

    /** One line nested under the one above it — see {@code ConfigCommands.indent}. */
    private static Component indent(Component line) {
        return Component.literal("  ").append(line);
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
