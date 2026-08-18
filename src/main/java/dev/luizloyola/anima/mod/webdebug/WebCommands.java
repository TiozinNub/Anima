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
 * {@code /anima web-debugger} — the discovery path, the switch, and who may look.
 *
 * <p><b>{@code start} runs it for this session and touches no setting.</b> That is the split worth
 * knowing: {@code web_debugger.enabled} is the AUTO-START switch, consulted once when a world
 * loads, and taking a look at a running world is not a decision about every future world.
 *
 * <p><b>Gated whole, unlike the rest of {@code /anima}.</b> Every node here either exposes a debug
 * surface or hands a browser standing permission to drive agents. There is no longer a node in the
 * group that does neither, so the gate sits on the root rather than on one child.
 */
public final class WebCommands {

    private WebCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        SuggestionProvider<CommandSourceStack> waiting = (ctx, builder) ->
                SharedSuggestionProvider.suggest(
                        WebDebugger.browsers().waiting().stream()
                                .map(WebBrowsers.Waiting::key).toList(), builder);
        SuggestionProvider<CommandSourceStack> allowed = (ctx, builder) ->
                SharedSuggestionProvider.suggest(WebDebugger.browsers().allowed(), builder);
        return Commands.literal("web-debugger")
                // The one gated root under /anima, which is otherwise ungated. Every node here
                // either exposes a debug surface or hands a browser standing permission to drive
                // agents, so there is no node left that an ordinary player wants.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("start").executes(ctx -> start(ctx.getSource())))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("access").executes(ctx -> access(ctx.getSource())))
                .then(Commands.literal("allow")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(waiting)
                                .executes(ctx -> allow(ctx.getSource(), name(ctx)))))
                .then(Commands.literal("dismiss")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(waiting)
                                .executes(ctx -> dismiss(ctx.getSource(), name(ctx)))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(allowed)
                                .executes(ctx -> remove(ctx.getSource(), name(ctx)))));
    }

    private static String name(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
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

    /** Starts it whatever {@code web_debugger.enabled} says, and restarts a running one. */
    private static int start(CommandSourceStack source) {
        String problem = WebDebugger.start(source.getServer());
        if (problem != null) {
            // The problem itself is the socket's own complaint, and arrives in one language.
            Replies.fail(source, Component.translatable("anima.webdebug.start_failed", problem));
            return 0;
        }
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
        if (WebDebugger.browsers().allowed().isEmpty()) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.none_allowed")
                    .withStyle(ChatFormatting.YELLOW)));
        }
        // Said here as well as in the log: whoever is reading this is the person who can undo it,
        // and the log line scrolls past on a busy server.
        if (!WebDebugger.loopbackOnly()) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.exposed",
                    WebDebugger.host()).withStyle(ChatFormatting.RED)));
        }
    }

    // --- who may look -----------------------------------------------------------------------

    private static int access(CommandSourceStack source) {
        WebBrowsers browsers = WebDebugger.browsers();
        List<WebBrowsers.Waiting> waiting = browsers.waiting();
        List<String> allowed = browsers.allowed();

        Replies.send(source, () -> Component.translatable("anima.webdebug.list_title")
                .withStyle(ChatFormatting.AQUA));

        if (waiting.isEmpty()) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.nobody_asking")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }
        for (WebBrowsers.Waiting browser : waiting) {
            Replies.send(source, () -> indent(Component.literal(browser.key())
                    .withStyle(ChatFormatting.WHITE)
                    .append(Component.translatable("anima.webdebug.asked_from", browser.from(),
                            since(browser.askedAtMillis())).withStyle(ChatFormatting.DARK_GRAY))
                    .append(button(" [allow]",
                            "/anima web-debugger allow " + browser.key(),
                            "anima.webdebug.hover.allow"))
                    .append(button(" [dismiss]",
                            "/anima web-debugger dismiss " + browser.key(),
                            "anima.webdebug.hover.dismiss"))));
        }
        if (allowed.isEmpty()) {
            Replies.send(source, () -> indent(Component.translatable("anima.webdebug.none_yet")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }
        for (String key : allowed) {
            Replies.send(source, () -> indent(Component.literal(key)
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.translatable("anima.webdebug.is_allowed")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(button(" [remove]", "/anima web-debugger remove " + key,
                            "anima.webdebug.hover.remove"))));
        }
        return waiting.size() + allowed.size();
    }

    private static int allow(CommandSourceStack source, String key) {
        WebBrowsers.Admission admission = WebDebugger.browsers().allow(key);
        switch (admission) {
            case MALFORMED -> {
                Replies.fail(source, Component.translatable("anima.webdebug.malformed_name", key));
                return 0;
            }
            case ALREADY -> {
                Replies.send(source, () -> Component.translatable("anima.webdebug.already", key)
                        .withStyle(ChatFormatting.GRAY));
                return 0;
            }
            default -> {
                // LOGGED: this is the grant. Nothing else in the world records that it happened.
                Replies.send(source, () -> Component.translatable("anima.webdebug.allowed", key)
                        .withStyle(ChatFormatting.GREEN), true);
                return 1;
            }
        }
    }

    private static int dismiss(CommandSourceStack source, String key) {
        if (!WebDebugger.browsers().dismiss(key)) {
            Replies.send(source, () -> Component.translatable("anima.webdebug.not_asking", key)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("anima.webdebug.dismissed", key)
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private static int remove(CommandSourceStack source, String key) {
        // Through WebDebugger, not the register: removing has to close the stream that browser is
        // already holding, or it takes effect whenever the page next happens to reconnect.
        if (!WebDebugger.remove(key)) {
            Replies.send(source, () -> Component.translatable("anima.webdebug.not_allowed", key)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        // LOGGED, for the reason allow is: it is the other half of the same decision.
        Replies.send(source, () -> Component.translatable("anima.webdebug.removed", key)
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
                .append(button(" [allow]", "/anima web-debugger allow " + key,
                        "anima.webdebug.hover.allow_this"))
                .append(button(" [dismiss]", "/anima web-debugger dismiss " + key,
                        "anima.webdebug.hover.dismiss"));
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
