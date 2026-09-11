package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Menu;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.SpeechEngine;
import dev.luizloyola.anima.mod.net.ContactsSync;
import dev.luizloyola.anima.mod.social.Talkers;
import java.util.List;
import java.util.random.RandomGenerator;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * What a player says into a conversation — social foundations §8's response set, as a command the
 * buttons run.
 *
 * <p><b>Its own root, and NOT op-gated.</b> {@code /anima} is gated whole because every node under
 * it drives an agent or edits the machinery; this is the first verb an ordinary player is meant to
 * have, and Brigadier drops a failing root from a non-op's tree entirely — so it cannot live
 * there. The hyphen keeps the namespace without claiming a word as common as {@code /say}.
 *
 * <p><b>The buttons are a hint; this is the law.</b> A menu was rendered a beat ago and the record
 * may have moved since — the counterpart may have spoken, ended the chat, or run the turn cap out.
 * So every pick is re-checked against the live record through {@link Menu}, and a refusal says
 * which of the reasons it was rather than quietly doing nothing.
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

    /** Bare {@code /anima-say}: show the menu again, for a player whose chat has scrolled. */
    private static int show(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = talker(source);
        if (player == null) {
            return 0;
        }
        if (!Talkers.offer(source.getServer(), player)) {
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
        MinecraftServer server = source.getServer();
        Speech speech = Talkers.of(server, player);
        Encounter e = speech.current().orElse(null);
        if (e == null) {
            Replies.fail(source, Component.translatable("anima.talk.not_talking"));
            return 0;
        }
        long now = server.overworld().getGameTime();
        // The JDK's shared generator rather than the player's: a RandomSource is not a
        // RandomGenerator (BrainDriver seeds its own AgentRandom across that same gap), and the
        // only thing drawn here is which flavour a topicless small talk lands on.
        Menu.Pick pick = Menu.pick(e, ContactsSync.idOf(player), now, cap(e, now),
                StringArgumentType.getString(ctx, "act"), topic, RandomGenerator.getDefault());
        if (!pick.ok()) {
            Replies.fail(source, Component.translatable(refusal(pick.reason())));
            return 0;
        }
        speech.say(e, pick.line());
        return 1;
    }

    /**
     * Walking away — §8's ignore, which is "both a non-action and a button". It writes the very
     * line the counterpart's patience clock would have written a quarter-minute later, about the
     * same subject: this player stopped answering. IGNORED ends a record, so the engine closes it
     * on the way out and the other side's {@code Converse} reads it closed on its next tick.
     */
    private static int leave(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = talker(source);
        if (player == null) {
            return 0;
        }
        Speech speech = Talkers.of(source.getServer(), player);
        Encounter e = speech.current().orElse(null);
        if (e == null) {
            Replies.fail(source, Component.translatable("anima.talk.not_talking"));
            return 0;
        }
        speech.system(e, SpeechActs.IGNORED, ContactsSync.idOf(player));
        return 1;
    }

    // ── the pieces ───────────────────────────────────────────────────────────────────────────

    /** The source as a player, having said why when it is not one — a console cannot converse. */
    private static @Nullable ServerPlayer talker(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Replies.fail(source, Component.translatable("anima.talk.not_a_player"));
        }
        return player;
    }

    /** What this source could say right now — empty for a console, or for nobody talking. */
    private static Iterable<SpeechAct> offered(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return List.of();
        }
        MinecraftServer server = source.getServer();
        Encounter e = Talkers.of(server, player).current().orElse(null);
        if (e == null) {
            return List.of();
        }
        long now = server.overworld().getGameTime();
        return Menu.offered(e, ContactsSync.idOf(player), now, cap(e, now));
    }

    /** The turn cap this record is under right now — the duration cap narrows it to 0. */
    private static int cap(Encounter e, long now) {
        return SpeechEngine.turnCap(e, now, Config.get().i(Knob.SOCIAL_ENCOUNTER_TURN_CAP),
                Config.get().i(Knob.SOCIAL_ENCOUNTER_TICK_CAP));
    }

    /** One lang key per refusal — a player who is told "no" is owed which no it was. */
    private static String refusal(Menu.Reason reason) {
        return switch (reason) {
            case UNKNOWN -> "anima.talk.unknown";
            case NOT_OFFERED -> "anima.talk.not_offered";
            case TOO_SOON -> "anima.talk.too_soon";
            case BAD_TOPIC -> "anima.talk.bad_topic";
            case OK -> throw new IllegalStateException("an accepted pick has no refusal");
        };
    }
}
