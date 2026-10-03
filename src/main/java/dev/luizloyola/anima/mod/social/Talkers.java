package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.compat.inv.GiveMenu;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.task.Converse;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Encounters;
import dev.luizloyola.anima.core.social.speech.Handover;
import dev.luizloyola.anima.core.social.speech.Menu;
import dev.luizloyola.anima.core.social.speech.Parting;
import dev.luizloyola.anima.core.social.speech.Picker;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.SpeechEngine;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.net.ContactsSync;
import dev.luizloyola.anima.mod.net.TalkActionPayload;
import dev.luizloyola.anima.mod.net.TalkPayload;
import dev.luizloyola.anima.mod.net.TalkSync;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A player's seat at a conversation — the half of participating that a brain does for a settler
 * and nothing does for a player: an engine on the world roster, the wait noticed, and a panel of
 * what may be said.
 *
 * <p><b>The sweep is the player's participation.</b> Nothing ticks the record (social foundations
 * §5) — its participants do, each on its own tick. A player has no tick of their own, so this runs
 * one for every online player with an open record, doing exactly what {@code Converse} does for a
 * settler each tick: notice a stale record, call the snub on an unanswered question, notice a body
 * that never came, and — the part a chooser does for a settler — put the applicable acts in front
 * of the player, as buttons on the panel, once their beat has elapsed.
 *
 * <p><b>The panel is fed, never polled.</b> The client draws the last {@link TalkPayload} it was
 * sent and nothing else: opened on the tap, refreshed as each line lands (their line with the acts
 * under it, the player's own with none — the word is theirs), and closed when the record does.
 * Nothing opens it but the tap: a settler walking up and greeting gets a bubble and an action-bar
 * hint, and the player's own click opens the panel into that record (decision: Luiz, 2026-09-14).
 * Closing it ends nothing (decision: Luiz, 2026-09-23): the conversation is set aside, and ends
 * only when the player walks away or leaves it set aside past the counterpart's patience.
 *
 * <p><b>The acts are live the moment they are offered</b> (decision: Luiz, 2026-09-14): a reply's
 * pause is the replier's — a settler waits its beat before answering, a player answers when they
 * click. What the panel withholds is a SECOND line of the player's own before anybody answered
 * the first: after a line of theirs, or a tap, which the body answers with a greeting, the buttons
 * wait {@link #OWN_LINE_GRACE} for the answer, and come back if the settler is holding its
 * tongue. The buttons are a hint; every pick is re-derived from the live record.
 *
 * <p><b>A player borrows the counterpart's numbers.</b> No species, so the chat radius a line
 * carries and the patience a wait is measured by are the counterpart's aspects while it is loaded,
 * and the settler's values otherwise (rung 7, 2026-09-11).
 */
public final class Talkers {

    /** The settler's values, for a counterpart with no loaded body to read them off. */
    static final int FALLBACK_CHAT_RADIUS = 12;
    static final int FALLBACK_PATIENCE_TICKS = 300;

    /**
     * How long a line of the player's own — or a tap, which the body answers with a greeting —
     * keeps the acts away: the floor plus the widest jitter a settler rolls, so an answer that is
     * coming lands before the buttons do. A settler holding its tongue on purpose (nothing owed,
     * the goodbye waiting out its silence) then leaves the player buttons to click rather than a
     * panel with nothing on it (2026-09-14).
     */
    static final int OWN_LINE_GRACE = Picker.REPLY_GRACE_TICKS + Converse.JITTER_TICKS;

    /**
     * Beyond chat radius from where they stood for this long, a player has walked away — a step
     * back or a jump is not leaving, twelve blocks for two seconds is. It lets the record go the
     * way {@link #letGo} says; a settler's leaving is measured on the settler's clock instead
     * (patience), the way {@code Converse} trails off on anybody.
     */
    static final int WALKED_AWAY_TICKS = 40;

    /** How much of the record the panel shows. */
    static final int PANEL_LINES = 3;
    /** Below this much food a player shows hunger: natural healing stops at 18, sprinting at 6. */
    static final int HUNGRY_BELOW = 15;

    /**
     * A body still loaded but whose mind has left the conversation — something more pressing
     * took the wheel: a mob to flee, a fire — for this long, and the record closes. Between
     * settlers an interruption leaves the record open to resume ({@code Converse}'s own rule); a
     * player is not left holding a panel for a settler that ran off. Two seconds covers the tick
     * or two a tapped body takes to pick the conversation up in the first place.
     */
    static final int BROKE_OFF_TICKS = 40;

    private static final Map<MinecraftServer, Map<UUID, Talker>> BY_SERVER = new HashMap<>();

    /** One player's seat: the engine, what the panel is open on, and how much of which record has gone live. */
    private static final class Talker {
        final SpeechEngine engine;
        /** The record the panel is open on, or null while it is closed. */
        @Nullable UUID panelRecord;
        /** The record the player was told somebody is talking to them in — once per record. */
        @Nullable UUID hintedRecord;
        /** Which record, at what length, the panel last got buttons for — so the sweep sends them once. */
        UUID offeredRecord;
        int offeredLines = -1;
        /** Where the player stood when THIS record last moved — leaving is measured from here. */
        Vec3 anchor;
        UUID anchoredRecord;
        int anchoredLines = -1;
        /** The trailed-off clock; reset with the anchor when a fresh record replaces the last. */
        final Parting parting = new Parting();
        /** Ticks the counterpart's mind has been elsewhere — see {@link #BROKE_OFF_TICKS}. */
        int away;
        /**
         * The give screen is up. The chest replacing the panel is not the player putting the
         * conversation down, and the panel is not redrawn over the chest until it closes.
         */
        boolean giving;

        Talker(SpeechEngine engine) {
            this.engine = engine;
        }

        void offered(Encounter e) {
            offeredRecord = e.id();
            offeredLines = e.transcript().size();
        }

        boolean offeredFor(Encounter e) {
            return e.id().equals(offeredRecord) && e.transcript().size() == offeredLines;
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
     * Opens the panel on the player's conversation — the tap, and the bare {@code /anima-say} —
     * with buttons if the word is the player's. Returns whether a record was open at all, so the
     * caller can say so when not.
     */
    public static boolean open(MinecraftServer server, ServerPlayer player) {
        Talker talker = seat(server, player);
        Optional<Encounter> current = talker.engine.current();
        if (current.isEmpty()) {
            return false;
        }
        Encounter e = current.get();
        if (show(server, player, talker, e)) {
            talker.offered(e);
        }
        return true;
    }

    /**
     * A line landed in {@code e}. Every player in it with the panel open sees it now — the acts
     * under a line of theirs, none under the player's own. A player without the panel open is
     * told somebody is talking to them, once per record; nothing opens it for them.
     */
    static void refresh(MinecraftServer server, Encounter e, Utterance u) {
        if (u.system()) {
            return; // the world reporting on the record: the close reaches the panel from the sweep
        }
        for (AgentId id : e.participants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id.value());
            if (player == null) {
                continue;
            }
            Talker talker = seat(server, player);
            if (e.id().equals(talker.panelRecord)) {
                if (show(server, player, talker, e)) {
                    talker.offered(e);
                }
            } else if (!id.equals(u.author()) && !e.id().equals(talker.hintedRecord)) {
                talker.hintedRecord = e.id();
                Speeches.aside(player, Component.translatable("anima.talk.approach",
                        Speeches.nameFor(server, player, u.author()))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
        }
    }

    // ── what the player did ──────────────────────────────────────────────────────────────────

    /** The panel's word, on the server thread. A refusal goes to the action bar. */
    public static void act(MinecraftServer server, ServerPlayer player, TalkActionPayload action) {
        String refusal = action.close() ? putDown(server, player)
                : say(server, player, action.act(), action.topic().orElse(null));
        if (refusal != null) {
            Speeches.aside(player, Component.translatable(refusal)
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
    }

    /**
     * Says {@code act} into the player's conversation, re-derived from the live record — the
     * buttons were rendered a beat ago and it may have moved since. Returns the lang key of the
     * refusal, or null once said.
     */
    public static @Nullable String say(MinecraftServer server, ServerPlayer player, String act,
            @Nullable String topic) {
        Speech speech = of(server, player);
        Encounter e = speech.current().orElse(null);
        if (e == null) {
            return "anima.talk.not_talking";
        }
        long now = server.overworld().getGameTime();
        // The JDK's shared generator rather than the player's: a RandomSource is not a
        // RandomGenerator (BrainDriver seeds its own AgentRandom across that same gap), and the
        // only thing drawn here is which flavour a topicless small talk lands on.
        Menu.Pick pick = Menu.pick(e, ContactsSync.idOf(player), cap(e, now),
                ContactData.get(server)::knows, act, topic, RandomGenerator.getDefault());
        if (!pick.ok()) {
            return refusal(pick.reason());
        }
        if (!playerMay(player, e, pick.line().act())) {
            return refusal(Menu.Reason.NOT_OFFERED);
        }
        if (pick.line().act() == SpeechActs.GIVE) {
            return openGive(server, player, e);
        }
        Chooser.Line line = pick.line();
        // A chat left to the draw asks the consumer first: the sky over the player, their pack,
        // the settler in front of them — never the same thing twice (decision: Luiz, 2026-09-24).
        // The menu's own draw is the fallback for an install with no vocabulary to ask.
        Optional<PlayerTopics.Source> topics = PlayerTopics.source();
        if (topic == null && !line.act().topics().isEmpty() && topics.isPresent()) {
            Optional<Map<String, String>> payload = topics.get().pick(server, player, e, line.act());
            if (payload.isEmpty()) {
                return "anima.talk.nothing_new";
            }
            line = new Chooser.Line(line.act(), payload.get());
        }
        speech.say(e, line);
        return null;
    }

    /**
     * Leaving on purpose — {@code /anima-say leave}; the panel's Esc only puts the conversation
     * down ({@link #putDown}). The server reads it for what it is (decision: Luiz, 2026-09-14): with their
     * goodbye pending on the player it is the acknowledgement — leaving somebody who said goodbye
     * is no snub — and otherwise it is the verdict, IGNORED about oneself, the very line the
     * counterpart's patience clock would have written a quarter-minute later. Either ends the
     * record; the other side's {@code Converse} reads it closed on its next tick. Returns the
     * refusal key, or null once done.
     */
    public static @Nullable String leave(MinecraftServer server, ServerPlayer player) {
        Talker talker = seat(server, player);
        Encounter e = talker.engine.current().orElse(null);
        if (e == null) {
            return "anima.talk.not_talking";
        }
        AgentId self = ContactsSync.idOf(player);
        talker.panelRecord = null;
        Optional<SpeechAct> farewell = Picker.pendingOn(e, self)
                .flatMap(pending -> SpeechActs.byKey(pending.act()))
                .filter(SpeechAct::ends);
        if (farewell.isPresent()) {
            talker.engine.say(e, Chooser.Line.of(farewell.get()));
        } else {
            talker.engine.system(e, SpeechActs.IGNORED, self);
        }
        return null;
    }

    /**
     * The panel went away with the record still open — Esc, or another screen over it. Nothing is
     * said and nothing is decided (decision: Luiz, 2026-09-23): a player steps back to reposition,
     * and the conversation waits for them. Walking away still ends it ({@link #parted}), and so
     * does leaving it set aside past the counterpart's patience; talking to them again picks it
     * back up. Never refuses — a record that closed under the panel is simply nothing to set aside.
     */
    public static @Nullable String putDown(MinecraftServer server, ServerPlayer player) {
        Talker talker = seat(server, player);
        if (talker.giving) {
            return null; // the give screen took the panel's place; that is not setting it aside
        }
        talker.panelRecord = null;
        Encounter e = talker.engine.current().orElse(null);
        if (e == null) {
            return null;
        }
        talker.parting.putDown(server.overworld().getGameTime());
        // Said here, once, so the settler's next line does not replace it with "is talking to you".
        talker.hintedRecord = e.id();
        Speeches.aside(player, Component.translatable("anima.talk.set_aside",
                        Speeches.nameFor(server, player, talker.engine.counterpart(e).orElse(null)))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        return null;
    }

    /**
     * Lets the record go without a word from the player — walked away, or set aside too long.
     * Their goodbye pending closes it quietly: the last line already says why it ended, and putting
     * a "bye" in the player's mouth they never clicked would be the seat speaking for them.
     * Otherwise it trailed off on the player, IGNORED about themself.
     */
    private static void letGo(Talker talker, Encounter e, AgentId self) {
        boolean farewellPending = Picker.pendingOn(e, self)
                .flatMap(pending -> SpeechActs.byKey(pending.act()))
                .map(SpeechAct::ends)
                .orElse(false);
        if (farewellPending) {
            talker.engine.close(e);
        } else {
            talker.engine.system(e, SpeechActs.IGNORED, self);
        }
    }

    /** What this player could say right now — for the command's suggestions; empty for nobody talking. */
    public static List<SpeechAct> offered(MinecraftServer server, ServerPlayer player) {
        Encounter e = of(server, player).current().orElse(null);
        if (e == null) {
            return List.of();
        }
        long now = server.overworld().getGameTime();
        return Menu.offered(e, ContactsSync.idOf(player), cap(e, now),
                ContactData.get(server)::knows);
    }

    // ── the sweep ────────────────────────────────────────────────────────────────────────────

    private static void sweep(MinecraftServer server) {
        Encounters roster = EncounterData.get(server).roster();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AgentId self = ContactsSync.idOf(player);
            if (roster.openFor(self).isEmpty()) {
                // Not talking. A panel still open is on a record that just closed — the other
                // side's goodbye, a verdict, the caps — and the client is told now.
                Map<UUID, Talker> seated = BY_SERVER.get(server);
                Talker idle = seated == null ? null : seated.get(player.getUUID());
                if (idle != null && idle.panelRecord != null) {
                    idle.panelRecord = null;
                    TalkSync.panel(player, TalkPayload.closed());
                }
                continue;
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
            if (talker.engine.lapsedFarewell(e)) {
                talker.engine.close(e); // their goodbye went unanswered: the door closes, no verdict
                continue;
            }
            long now = server.overworld().getGameTime();
            if (Picker.unanswered(e, other, now, patienceOf(server, Optional.of(other)))) {
                talker.engine.system(e, SpeechActs.IGNORED, other);
                notice(server, player, "anima.talk.ignored", other);
                continue;
            }
            if (parted(server, player, talker, e, other, now)) {
                continue;
            }
            if (brokeOff(server, player, talker, e, other)) {
                continue;
            }
            if (talker.parting.dropped(now, patienceOf(server, Optional.of(other)))) {
                letGo(talker, e, self);
                notice(server, player, "anima.talk.dropped", other);
                continue;
            }
            if (talker.panelRecord == null || talker.offeredFor(e)) {
                continue;
            }
            // The word was theirs and they are holding their tongue: the buttons come back.
            if (!theirTurn(server, player, e) && show(server, player, talker, e)) {
                talker.offered(e);
            }
        }
    }

    /**
     * Whether the word is theirs: after a line of the player's own — or a tap, which the body
     * answers with a greeting — the body gets {@link #OWN_LINE_GRACE} to answer before a second
     * line of the player's is offered.
     */
    private static boolean theirTurn(MinecraftServer server, ServerPlayer player, Encounter e) {
        AgentId self = ContactsSync.idOf(player);
        long now = server.overworld().getGameTime();
        Optional<Utterance> last = Picker.lastSpoken(e);
        boolean mine = last.map(line -> self.equals(line.author())).orElse(true);
        long since = last.map(Utterance::tick).orElse(e.openedAt());
        return mine && now - since <= OWN_LINE_GRACE;
    }

    /**
     * Whether the two have come apart, and closes the record once whoever left has been gone long
     * enough. Distance is symmetric, so who LEFT is read off who moved: the player, measured from
     * where they stood when the record last moved; the body, measured from the player — or gone
     * altogether: unloaded, dead, in another dimension. A player who walked away is called on it
     * in two seconds, since they cannot read a line from twelve blocks anyway; a body that walked
     * off gets the patience a settler would give it, and the clock resets if it comes back.
     *
     * <p>The anchor and the clock belong to ONE record. Keyed on the line count alone, a fresh
     * record whose first sweep found the same count as the last anchor kept that anchor — from the
     * spot the player had walked away from — and closed on its own greeting (2026-09-14).
     */
    private static boolean parted(MinecraftServer server, ServerPlayer player, Talker talker,
            Encounter e, AgentId other, long now) {
        boolean fresh = !e.id().equals(talker.anchoredRecord);
        if (fresh || talker.anchoredLines != e.transcript().size()) {
            talker.anchor = player.position();
            talker.anchoredRecord = e.id();
            talker.anchoredLines = e.transcript().size();
            if (fresh) {
                talker.parting.reset();
                talker.away = 0;
            }
        }
        double reach = radiusOf(server, Optional.of(other));
        reach *= reach;
        boolean playerLeft = player.position().distanceToSqr(talker.anchor) > reach;
        AgentBody body = AgentBodies.findLoaded(server, other);
        boolean bodyGone = body == null || !body.entity().isAlive()
                || body.entity().level() != player.level()
                || player.distanceToSqr(body.entity()) > reach;
        switch (talker.parting.tick(now, playerLeft, bodyGone, WALKED_AWAY_TICKS,
                patienceOf(server, Optional.of(other)))) {
            case SELF_LEFT -> {
                letGo(talker, e, ContactsSync.idOf(player));
                return true;
            }
            case OTHER_GONE -> {
                talker.engine.system(e, SpeechActs.IGNORED, other);
                notice(server, player, "anima.talk.left", other);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Whether the body's mind has left the conversation for good — not conversing for
     * {@link #BROKE_OFF_TICKS} while loaded. An unloaded body is {@link #parted}'s business. The
     * verdict is INTERRUPTED about the body: no snub, since nobody chose to stop talking.
     */
    private static boolean brokeOff(MinecraftServer server, ServerPlayer player, Talker talker,
            Encounter e, AgentId other) {
        AgentBody body = AgentBodies.findLoaded(server, other);
        if (body == null || body.brain().conversing()) {
            talker.away = 0;
            return false;
        }
        if (++talker.away < BROKE_OFF_TICKS) {
            return false;
        }
        talker.engine.system(e, SpeechActs.INTERRUPTED, other);
        notice(server, player, "anima.talk.interrupted", other);
        return true;
    }

    // ── the panel ────────────────────────────────────────────────────────────────────────────

    /**
     * Sends the panel its whole state: who, the last {@link #PANEL_LINES} lines as this player
     * reads them, and the acts — none while the word is theirs, or while the picker refuses the
     * player a turn. Composed here and drawn there; the client keeps nothing. Returns whether
     * any act went with it.
     */
    private static boolean show(MinecraftServer server, ServerPlayer player, Talker talker,
            Encounter e) {
        if (talker.giving) {
            return false; // drawn again once the give screen closes
        }
        AgentId self = ContactsSync.idOf(player);
        AgentId other = e.other(self).orElse(null);
        long now = server.overworld().getGameTime();

        List<TalkPayload.Line> lines = new ArrayList<>();
        List<Utterance> transcript = e.transcript();
        for (int i = transcript.size() - 1; i >= 0 && lines.size() < PANEL_LINES; i--) {
            Utterance u = transcript.get(i);
            Component text = Speeches.spoken(server, e, i);
            if (text == null) {
                continue;
            }
            boolean mine = self.equals(u.author());
            lines.add(new TalkPayload.Line(mine,
                    mine ? player.getName() : Speeches.nameFor(server, player, u.author()),
                    mine ? Optional.empty() : Portraits.of(server, u.author()),
                    text));
        }
        Collections.reverse(lines);

        List<TalkPayload.Offer> offers = new ArrayList<>();
        if (!theirTurn(server, player, e)) {
            Optional<PlayerTopics.Source> topics = PlayerTopics.source();
            for (SpeechAct act : Menu.offered(e, self, cap(e, now), ContactData.get(server)::knows)) {
                if (!playerMay(player, e, act)) {
                    continue;
                }
                if (!act.topics().isEmpty() && topics.isPresent()
                        && !topics.get().anythingLeft(server, player, e, act)) {
                    continue; // nothing left to say with it: the goodbye is still there
                }
                offers.add(new TalkPayload.Offer(act.key(),
                        Component.translatable(act.langKey() + ".button"
                                + (act == SpeechActs.GIVE && askedForFood(e, self) ? ".asked" : "")),
                        hover(server, player, act)));
            }
        }

        AgentBody body = other == null ? null : AgentBodies.findLoaded(server, other);
        TalkPayload.Counterpart who = new TalkPayload.Counterpart(
                body == null ? -1 : body.entity().getId(),
                Speeches.nameFor(server, player, other),
                other == null ? Optional.empty() : Portraits.of(server, other));
        talker.panelRecord = e.id();
        talker.parting.pickedUp();
        TalkSync.panel(player, new TalkPayload(true, who, lines, offers));
        return !offers.isEmpty();
    }

    /**
     * What a player may say beyond the menu's own rules, read off the player: asking for food only
     * while hungry — below {@link #HUNGRY_BELOW}, as a body with no gauges shows it — and once a
     * chat; and holding something out not at all until the give screen exists, since a player's
     * offer is made through it (2026-10-02-food-and-replies-design.md).
     */
    private static boolean playerMay(ServerPlayer player, Encounter e, SpeechAct act) {
        if (act == SpeechActs.OFFER || act == SpeechActs.NOT_WANTED) {
            return false; // a player hands things over through the give screen, and takes what comes
        }
        if (act == SpeechActs.ASK_FOOD) {
            AgentId self = ContactsSync.idOf(player);
            return player.getFoodData().getFoodLevel() < HUNGRY_BELOW
                    && e.transcript().stream().noneMatch(u -> self.equals(u.author())
                            && u.act().equals(SpeechActs.ASK_FOOD.key()));
        }
        return true;
    }

    private static boolean askedForFood(Encounter e, AgentId self) {
        return Picker.pendingOn(e, self).filter(u -> u.act().equals(SpeechActs.ASK_FOOD.key()))
                .isPresent();
    }

    /**
     * Opens the give screen on the counterpart (2026-10-02-food-and-replies-design.md): as many
     * usable slots as they have free storage, up to nine. Returns a refusal key, or null once open.
     */
    private static @Nullable String openGive(MinecraftServer server, ServerPlayer player, Encounter e) {
        AgentId self = ContactsSync.idOf(player);
        AgentBody body = e.other(self).map(other -> AgentBodies.findLoaded(server, other)).orElse(null);
        if (body == null) {
            return "anima.talk.not_talking";
        }
        int room = 0;
        for (int slot = 0; slot < dev.luizloyola.anima.core.inv.Inventory.ARMOR_START; slot++) {
            if (body.inventory().get(slot).isEmpty()) {
                room++;
            }
        }
        if (room == 0) {
            return "anima.talk.no_room";
        }
        Talker talker = seat(server, player);
        talker.giving = true;
        GiveMenu.open(player, Component.translatable("anima.give.title",
                        Speeches.nameFor(server, player, body.agentId())),
                room, Component.translatable("anima.give.no_room"),
                (who, given) -> given(server, who, given));
        return null;
    }

    /**
     * The give screen closed: what was put in goes into the counterpart's pack, and the player says
     * the line that records it. A record that ended meanwhile, or a give no longer on offer, hands
     * everything back; whatever does not fit comes back too.
     */
    private static void given(MinecraftServer server, ServerPlayer player,
            List<net.minecraft.world.item.ItemStack> given) {
        Talker talker = seat(server, player);
        talker.giving = false;
        Encounter e = talker.engine.current().orElse(null);
        AgentId self = ContactsSync.idOf(player);
        AgentBody body = e == null ? null
                : e.other(self).map(other -> AgentBodies.findLoaded(server, other)).orElse(null);
        long now = server.overworld().getGameTime();
        boolean offered = e != null && body != null && Menu.offered(e, self, cap(e, now),
                ContactData.get(server)::knows).contains(SpeechActs.GIVE);
        Map<String, Integer> moved = new java.util.LinkedHashMap<>();
        for (net.minecraft.world.item.ItemStack stack : given) {
            if (!offered) {
                GiveMenu.giveBack(player, stack);
                continue;
            }
            dev.luizloyola.anima.core.inv.ItemStack core = ItemStacks.toCore(stack, server.registryAccess());
            dev.luizloyola.anima.core.inv.ItemStack left = body.inventory().add(core);
            if (left.count() < core.count()) {
                moved.merge(core.id(), core.count() - left.count(), Integer::sum);
            }
            if (!left.isEmpty()) {
                GiveMenu.giveBack(player, ItemStacks.toVanilla(left, server.registryAccess()));
            }
        }
        if (!moved.isEmpty()) {
            List<Handover.Item> items = new ArrayList<>();
            moved.forEach((id, count) -> items.add(new Handover.Item(id, count)));
            talker.engine.say(e, new Chooser.Line(SpeechActs.GIVE, Handover.payload(items)));
        } else if (e != null && e.id().equals(talker.panelRecord)) {
            show(server, player, talker, e); // nothing given: the panel comes back as it was
        }
    }

    /**
     * A SAMPLE of how this act comes out — its first variant, about its first topic. Not a
     * promise: the real variant is derived from the record and the line's position
     * ({@code Speeches.variantOf}), and a topic left unsaid is drawn when the line is picked. So a
     * tooltip reading "Fine day for it." may be spoken as "Sky's been kind lately."
     *
     * <p>Deliberately not resolved exactly. Doing so would mean deciding the variant and the topic
     * here, at render time, and carrying both through the pick into the utterance — turning a
     * tooltip into state the conversation has to keep. What the button promises is the ACT.
     */
    private static Component hover(MinecraftServer server, ServerPlayer player, SpeechAct act) {
        Map<String, String> payload = act.topics().isEmpty() ? Map.of()
                : Map.of(Utterance.TOPIC, act.topics().get(0));
        String key = Speeches.renderKey(act, payload, 1);
        return act.introduces()
                ? Component.translatable(key, player.getName().getString())
                : Component.translatable(key);
    }

    /**
     * The player's own read of what just happened — social foundations §8's "small on-screen
     * line", on the action bar at last: who it was, and that they never answered.
     */
    private static void notice(MinecraftServer server, ServerPlayer player, String key, AgentId whom) {
        Speeches.aside(player, Component.translatable(key, Speeches.nameFor(server, player, whom))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
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
            case BAD_TOPIC -> "anima.talk.bad_topic";
            case OK -> throw new IllegalStateException("an accepted pick has no refusal");
        };
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
     * A player's engine has no chooser — what they say comes off the panel, decided by a person
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
