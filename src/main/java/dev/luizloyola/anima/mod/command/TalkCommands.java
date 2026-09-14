package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.mod.social.Talkers;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * What a player says into a conversation, as a command — the headless harness beside the panel,
 * and the same law: {@link Talkers#say} and {@link Talkers#leave} serve both.
 *
 * <p><b>Its own root, and NOT op-gated.</b> {@code /anima} is gated whole because every node under
 * it drives an agent or edits the machinery; this is the first verb an ordinary player is meant to
 * have, and Brigadier drops a failing root from a non-op's tree entirely — so it cannot live
 * there. The hyphen keeps the namespace without claiming a word as common as {@code /say}.
 *
 * <p>{@code say} and {@code leave} are literals rather than one act argument with a magic value:
 * the act registry is open, and a consumer registering an act called {@code leave} must not shadow
 * the way out of a conversation.
 */
public final class TalkCommands {

    /** Only what the picker would take right now — a suggestion that lies is worse than none. */
    private static final SuggestionProvider<CommandSourceStack> ACTS = (ctx, builder) -> {
        for (SpeechAct act : offered(ctx.getSource())) {
            builder.suggest(act.key());
        }
        return builder.buildFuture();
    };

    /** The topics the act already named in the command line declares, if it declares any. */
    private static final SuggestionProvider<CommandSourceStack> TOPICS = (ctx, builder) -> {
        SpeechActs.byKey(StringArgumentType.getString(ctx, "act"))
                .ifPresent(act -> act.topics().forEach(builder::suggest));
        return builder.buildFuture();
    };

    private TalkCommands() {
    }

    /** Registers {@code /anima-say …}. Call once from mod init. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("anima-say")
                        .executes(TalkCommands::show)
                        .then(Commands.literal("leave").executes(TalkCommands::leave))
                        .then(Commands.literal("say")
                                .then(Commands.argument("act", StringArgumentType.word())
                                        .suggests(ACTS)
                                        .executes(ctx -> say(ctx, null))
                                        .then(Commands.argument("topic", StringArgumentType.word())
                                                .suggests(TOPICS)
                                                .executes(ctx -> say(ctx,
                                                        StringArgumentType.getString(ctx, "topic")))
                                        )))));
    }

    /** Bare {@code /anima-say}: open the panel again on whatever conversation the player is in. */
    private static int show(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = talker(source);
        if (player == null) {
            return 0;
        }
        if (!Talkers.open(source.getServer(), player)) {
            Replies.fail(source, Component.translatable("anima.talk.not_talking"));
            return 0;
        }
        return 1;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, @Nullable String topic) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = talker(source);
        if (player == null) {
            return 0;
        }
        return done(source, Talkers.say(source.getServer(), player,
                StringArgumentType.getString(ctx, "act"), topic));
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = talker(source);
        if (player == null) {
            return 0;
        }
        return done(source, Talkers.leave(source.getServer(), player));
    }

    // ── the pieces ───────────────────────────────────────────────────────────────────────────

    /** A refusal is said back; null means it was done. */
    private static int done(CommandSourceStack source, @Nullable String refusal) {
        if (refusal != null) {
            Replies.fail(source, Component.translatable(refusal));
            return 0;
        }
        return 1;
    }

    /** The source as a player, having said why when it is not one — a console cannot converse. */
    private static @Nullable ServerPlayer talker(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Replies.fail(source, Component.translatable("anima.talk.not_a_player"));
        }
        return player;
    }

    /** What this source could say right now — empty for a console, or for nobody talking. */
    private static List<SpeechAct> offered(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? List.of() : Talkers.offered(source.getServer(), player);
    }
}
