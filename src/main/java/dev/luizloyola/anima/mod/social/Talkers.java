package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Encounters;
import dev.luizloyola.anima.core.social.speech.Menu;
import dev.luizloyola.anima.core.social.speech.Picker;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.SpeechEngine;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.net.ContactsSync;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * A player's seat at a conversation — the half of participating that a brain does for a settler
 * and nothing does for a player: an engine on the world roster, the wait noticed, and a menu of
 * what may be said.
 *
 * <p><b>The sweep is the player's participation.</b> Nothing ticks the record (social foundations
 * §5) — its participants do, each on its own tick. A player has no tick of their own, so this runs
 * one for every online player with an open record, doing exactly what {@code Converse} does for a
 * settler each tick: notice a stale record, call the snub on an unanswered question, notice a body
 * that never came, and — the part a chooser does for a settler — put the applicable acts in front
 * of the player as buttons once their beat has elapsed.
 *
 * <p><b>The menu renders on the beat, not on the line.</b> Buttons under a line the tick it lands
 * would offer what the picker refuses for the next twenty ticks. So the sweep renders once
 * {@code maySpeak} holds and the record has grown since the last render — and never straight
 * after a line of the player's own, or a second line would be offered before anybody answered the
 * first. The buttons are a hint; {@code /anima-say} re-derives the menu from the live record.
 *
 * <p><b>A player borrows the counterpart's numbers.</b> No species, so the chat radius a line
 * carries and the patience a wait is measured by are the counterpart's aspects while it is loaded,
 * and the settler's values otherwise (rung 7, 2026-09-11).
 */
public final class Talkers {

    /** The settler's values, for a counterpart with no loaded body to read them off. */
    static final int FALLBACK_CHAT_RADIUS = 12;
    static final int FALLBACK_PATIENCE_TICKS = 300;

    private static final Map<MinecraftServer, Map<UUID, Talker>> BY_SERVER = new HashMap<>();

    /** One player's seat: the engine, and how much of which record the menu has been shown for. */
    private static final class Talker {
        final SpeechEngine engine;
        UUID renderedRecord;
        int renderedLines = -1;

        Talker(SpeechEngine engine) {
            this.engine = engine;
        }

        void shown(Encounter e) {
            renderedRecord = e.id();
            renderedLines = e.transcript().size();
        }

        boolean shownFor(Encounter e) {
            return e.id().equals(renderedRecord) && e.transcript().size() == renderedLines;
        }
    }

    private Talkers() {
    }

    /** Call once from mod init. */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Talkers::sweep);
        ServerLifecycleEvents.SERVER_STOPPING.register(BY_SERVER::remove);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Map<UUID, Talker> seated = BY_SERVER.get(server);
            if (seated != null) {
                seated.remove(handler.player.getUUID());
            }
        });
    }

    /** This player's port onto the world's conversations, seated on first use. */
    public static Speech of(MinecraftServer server, ServerPlayer player) {
        return seat(server, player).engine;
    }

    /**
     * Shows the menu now if there is anything to show — the bare {@code /anima-say}, and the
     * right-click on somebody the player is already talking with. Returns whether a record was
     * open at all, so the caller can say so when it was not.
     */
    public static boolean offer(MinecraftServer server, ServerPlayer player) {
        Talker talker = seat(server, player);
        Optional<Encounter> current = talker.engine.current();
        if (current.isEmpty()) {
            return false;
        }
        talker.renderedLines = -1;
        render(server, player, talker, current.get());
        return true;
    }

    /** What this player has just said — the menu is not re-offered on their own line. */
    public static void spoke(MinecraftServer server, ServerPlayer player, Encounter e) {
        seat(server, player).shown(e);
    }

    // ── the sweep ────────────────────────────────────────────────────────────────────────────

    private static void sweep(MinecraftServer server) {
        Encounters roster = EncounterData.get(server).roster();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AgentId self = ContactsSync.idOf(player);
            if (roster.openFor(self).isEmpty()) {
                continue; // not talking: no seat needed, and none is made
            }
            Talker talker = seat(server, player);
            // current() is a mutating query — a stale record is closed right here, the same way
            // ConverseInstinct's pressure poll reaps one for a settler.
            Optional<Encounter> current = talker.engine.current();
            if (current.isEmpty()) {
                continue;
            }
            Encounter e = current.get();
            AgentId other = talker.engine.counterpart(e).orElse(null);
            if (other == null) {
                continue;
            }
            Optional<AgentId> snubbed = talker.engine.expiredObligation(e);
            if (snubbed.isPresent()) {
                talker.engine.system(e, SpeechActs.IGNORED, snubbed.get());
                notice(server, player, "anima.talk.unanswered", snubbed.get());
                continue;
            }
            long now = server.overworld().getGameTime();
            if (Picker.unanswered(e, other, now, patienceOf(server, Optional.of(other)))) {
                talker.engine.system(e, SpeechActs.IGNORED, other);
                notice(server, player, "anima.talk.ignored", other);
                continue;
            }
            if (talker.shownFor(e)) {
                continue;
            }
            Optional<Utterance> last = Picker.lastSpoken(e);
            if (last.isPresent() && self.equals(last.get().author())) {
                talker.shown(e); // their own line: wait for an answer rather than offer a second
                continue;
            }
            if (talker.engine.maySpeak(e)) {
                render(server, player, talker, e);
            }
        }
    }

    // ── the menu ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code » [Greet] [Ask their name] … [Walk away]} — one button per offered act, labelled by
     * its {@code <langKey>.button} and hovering the line it would say, plus the leave verb.
     */
    private static void render(MinecraftServer server, ServerPlayer player, Talker talker,
            Encounter e) {
        AgentId self = ContactsSync.idOf(player);
        long now = server.overworld().getGameTime();
        int cap = SpeechEngine.turnCap(e, now, Config.get().i(Knob.SOCIAL_ENCOUNTER_TURN_CAP),
                Config.get().i(Knob.SOCIAL_ENCOUNTER_TICK_CAP));
        List<SpeechAct> offered = Menu.offered(e, self, now, cap);
        talker.shown(e);
        if (offered.isEmpty()) {
            return;
        }
        MutableComponent line = Component.translatable("anima.talk.prompt")
                .withStyle(ChatFormatting.DARK_GRAY);
        for (SpeechAct act : offered) {
            line.append(" ").append(button(Component.translatable(act.langKey() + ".button"),
                    "/anima-say say " + act.key(), hover(server, player, act), ChatFormatting.AQUA));
        }
        line.append(" ").append(button(Component.translatable("anima.talk.leave"), "/anima-say leave",
                Component.translatable("anima.talk.leave.hover"), ChatFormatting.GRAY));
        player.sendSystemMessage(line);
    }

    /** The line this act would come out as — its first variant, about its first topic. */
    private static Component hover(MinecraftServer server, ServerPlayer player, SpeechAct act) {
        Map<String, String> payload = act.topics().isEmpty() ? Map.of()
                : Map.of(Utterance.TOPIC, act.topics().get(0));
        String key = Speeches.renderKey(act, payload, 1);
        return act.introduces()
                ? Component.translatable(key, player.getName().getString())
                : Component.translatable(key);
    }

    private static Component button(Component label, String command, Component hover,
            ChatFormatting color) {
        return Component.literal("[").append(label).append("]").withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    /**
     * The player's own read of what just happened (social foundations §8's "small on-screen
     * line"), beside the record's gray closing line rather than instead of it: the record says a
     * conversation trailed off, this says who it was and that they never answered.
     *
     * <p>Chat rather than the action bar — the whole conversation is already there, and the
     * {@code displayClientMessage} convenience is gone in 26.1, so the alternative would be a
     * packet in a Stonecutter block to say something quieter than it deserves.
     */
    private static void notice(MinecraftServer server, ServerPlayer player, String key, AgentId whom) {
        player.sendSystemMessage(Component.translatable(key, Speeches.nameFor(server, player, whom))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    // ── the seat ─────────────────────────────────────────────────────────────────────────────

    private static Talker seat(MinecraftServer server, ServerPlayer player) {
        return BY_SERVER.computeIfAbsent(server, s -> new HashMap<>())
                .computeIfAbsent(player.getUUID(), uuid -> new Talker(engine(server, player)));
    }

    /**
     * A player's engine. Every number it runs on is the COUNTERPART's, read live rather than
     * captured: a player has no species, and the body they are talking to may not even be loaded
     * when a line lands (it walked off, the chunk went away) — in which case the settler's own
     * values stand in, because a record still has to be paced and a wait still has to end.
     */
    private static SpeechEngine engine(MinecraftServer server, ServerPlayer player) {
        AgentId self = ContactsSync.idOf(player);
        Encounters roster = EncounterData.get(server).roster();
        Supplier<SpeechEngine.Caps> caps = () -> new SpeechEngine.Caps(
                Config.get().i(Knob.SOCIAL_ENCOUNTER_TURN_CAP),
                Config.get().i(Knob.SOCIAL_ENCOUNTER_TICK_CAP),
                Config.get().i(Knob.SOCIAL_ENCOUNTER_STALE_TICKS),
                patienceOf(server, counterpartOf(roster, self)));
        // Resolved per call rather than captured: a respawn or a dimension change hands the same
        // account a new entity, and the old one is where the line would otherwise be spoken from.
        Supplier<LivingEntity> writer = () -> {
            ServerPlayer live = server.getPlayerList().getPlayer(player.getUUID());
            return live != null ? live : player;
        };
        return new SpeechEngine(self, () -> server.overworld().getGameTime(), roster,
                () -> SILENT, caps,
                Speeches.listener(server, writer,
                        () -> radiusOf(server, counterpartOf(roster, self))));
    }

    /**
     * A player's engine has no chooser — what they say comes off the menu, decided by a person
     * rather than by a personality. Nothing calls this; silence is the honest answer if the day
     * ever comes that something does.
     */
    private static final Chooser SILENT = (ctx, turn) -> null;

    /** Who this player is talking to, or empty — read off the roster, never off the engine. */
    private static Optional<AgentId> counterpartOf(Encounters roster, AgentId self) {
        return roster.openFor(self).flatMap(e -> e.other(self));
    }

    private static int patienceOf(MinecraftServer server, Optional<AgentId> other) {
        return aspect(server, other, ProfileAspect.SOCIAL_PATIENCE_TICKS, FALLBACK_PATIENCE_TICKS);
    }

    private static int radiusOf(MinecraftServer server, Optional<AgentId> other) {
        return aspect(server, other, ProfileAspect.SOCIAL_CHAT_RADIUS, FALLBACK_CHAT_RADIUS);
    }

    /** {@code other}'s reading of {@code aspect}, or {@code fallback} with no body to ask. */
    private static int aspect(MinecraftServer server, Optional<AgentId> other,
            ProfileAspect aspect, int fallback) {
        AgentBody body = other.map(id -> AgentBodies.findLoaded(server, id)).orElse(null);
        return body == null ? fallback : body.profile().i(aspect);
    }
}
