package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.need.Company;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.brain.history.Doing;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Recounting;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.SpeechEngine;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.brain.BeingSpeech;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.net.ContactsSync;
import dev.luizloyola.anima.mod.net.TalkSync;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Where a spoken line becomes something a player sees — the one choke point between the speech
 * machinery and the screen: a bubble over the speaker's head for everyone in earshot, and the
 * panel for a player it is said to. Chat carries nothing of Anima's since 2026-09-14 (decision:
 * Luiz).
 *
 * <p><b>Names are composed per recipient.</b> Whoever knows that agent reads a name; everybody
 * else reads a stranger — on the panel and over the head, never in the bubble, which carries no
 * name at all. So {@link #nameFor} is asked per player, from their own contact book.
 *
 * <p><b>The writer is not the speaker.</b> {@code writer} is the body whose engine wrote the line
 * — a settler's, or a player's since rung 7 — and it supplies the position, the chat radius and so
 * the audience. WHO is shown comes from {@link Utterance#author()}, and the two differ on the
 * answerer's prefill of a hail it did not make (see {@code SpeechEngine.join}). Resolve a name or
 * a portrait from the author, never from the writer.
 *
 * <p><b>Rendering happens before the learn cascade, on purpose.</b> An introduction reads
 * "Someone: I'm Alma" — the prefix is what the listener knew a moment ago and the name in the line
 * is what they are being told. They read it as Alma from the next line on.
 */
public final class Speeches {

    /** What a listener who has not earned a name reads instead of one. */
    private static final String STRANGER = "anima.speech.someone";

    private Speeches() {}

    /**
     * Puts {@code u} over its speaker's head for everyone near enough to hear it, refreshes the
     * panel of whoever it was said to, and hands out the name it gave away. Called once as the
     * line lands — never by replaying a transcript, which would re-teach a name to a room that has
     * since forgotten it on purpose.
     */
    public static void deliver(MinecraftServer server, LivingEntity writer, int radius, Encounter e,
            Utterance u) {
        SpeechAct act = SpeechActs.byKey(u.act()).orElse(null);
        if (act == null) {
            // A word this install does not carry: silence beats a raw lang key in chat. System
            // lines are NOT skipped — "(the conversation trailed off)" is the fiction, not debug.
            return;
        }
        List<ServerPlayer> audience = within(server, writer, radius);
        AgentId author = u.author();
        Component line = spoken(server, e, e.transcript().size() - 1);
        Entity speaker = author == null ? null : bodyOf(server, author);
        if (line != null && speaker != null) {
            for (ServerPlayer player : audience) {
                TalkSync.bubble(player, speaker, line);
            }
        }
        Talkers.refresh(server, e, u);

        if (author != null && act.introduces()) {
            spread(server, e, writer, radius, audience, author, nameOf(server, author));
        }
    }

    /**
     * Line {@code index} of {@code e} as it is said — variant and topic resolved, the name an
     * introduction gives away spelled out, a deed told in its own words — or null for a system line
     * or a word this install lacks. The same text for every listener: a name in the line IS the
     * introduction, and a deed names only things, never people.
     */
    public static @Nullable Component spoken(MinecraftServer server, Encounter e, int index) {
        Utterance u = e.transcript().get(index);
        SpeechAct act = SpeechActs.byKey(u.act()).orElse(null);
        if (u.system() || act == null) {
            return null;
        }
        Optional<Recounting.Told> told = Recounting.read(u.payload());
        if (told.isPresent()) {
            return told(told.get(), variantOf(e.id(), index, Doing.VARIANTS));
        }
        String key = renderKey(act, u.payload(), variantOf(e.id(), index, act.variants()));
        return act.introduces()
                ? Component.translatable(key, nameOf(server, u.author()))
                : Component.translatable(key);
    }

    /**
     * What every engine does as a line lands — a settler's and a player's alike, which is why it
     * lives here and not in {@code BrainDriver}: mark the store, sound the voice, pay company, and
     * put the line in front of whoever can hear it.
     *
     * <p>A SYSTEM line (IGNORED, STALE, …) is the world reporting ON the conversation, not a party
     * speaking IN it — "a conversation is worth what was SAID" ({@code Company#conversed}'s own
     * doc), and nobody said this. No sound, no pay; {@link #deliver} still runs so players read the
     * gray line. Company is paid once per participant as the line arrives and never by replaying a
     * transcript, which would double-pay a resumed conversation; a participant with no loaded
     * body — a player, a settler in an unloaded chunk — is simply not paid.
     *
     * @param writer the body whose engine this is, resolved per call because an entity can be
     *     replaced under a live engine (a respawn, a dimension change)
     * @param radius that body's chat radius, read per call for the same reason
     */
    public static SpeechEngine.Listener listener(MinecraftServer server,
            Supplier<LivingEntity> writer, IntSupplier radius) {
        return new SpeechEngine.Listener() {
            @Override
            public void said(Encounter e, Utterance u) {
                EncounterData.get(server).dirty();
                LivingEntity body = writer.get();
                if (!u.system()) {
                    BeingSpeech.spoke(body);
                    for (AgentId participant : e.participants()) {
                        AgentBody paid = AgentBodies.findLoaded(server, participant);
                        if (paid != null) {
                            paid.needs().gauge(NeedKind.COMPANY, Company.class)
                                    .ifPresent(Company::conversed);
                        }
                    }
                }
                deliver(server, body, radius.getAsInt(), e, u);
            }

            @Override
            public void closed(Encounter e) {
                EncounterData data = EncounterData.get(server);
                data.dirty();
                // Pruning rides the close: cheap, and retention only ever trims closed records.
                data.prune(server.overworld().getGameTime());
            }
        };
    }

    /** The author as a client can find it — a loaded agent body, or the player themself; null when neither is around to draw over. */
    private static @Nullable Entity bodyOf(MinecraftServer server, AgentId author) {
        AgentBody body = AgentBodies.findLoaded(server, author);
        return body != null ? body.entity() : server.getPlayerList().getPlayer(author.value());
    }

    // ── the line ─────────────────────────────────────────────────────────────────────────────

    /**
     * The lang key one line renders through. A {@code topic} in the payload picks a sub-vocabulary
     * (a consumer's word may branch on what is being talked about); a single-variant act drops the
     * numeric suffix, so {@code anima.speech.deflect} is spelled without one.
     */
    static String renderKey(SpeechAct act, Map<String, String> payload, int variant) {
        String topic = payload.get("topic");
        String base = topic == null ? act.langKey() : act.langKey() + "." + topic;
        return act.variants() == 1 ? base : base + "." + variant;
    }

    /** A deed as a line says it: the doing's own words, its slots in order, then when. */
    public static Component told(Recounting.Told told, int variant) {
        List<Slot> slots = told.deed().slots();
        Object[] args = new Object[slots.size() + 1];
        for (int i = 0; i < slots.size(); i++) {
            args[i] = SlotNames.of(slots.get(i));
        }
        args[slots.size()] = Component.translatable(told.when().langKey());
        return Component.translatable(told.deed().doing().langKey() + "." + variant, args);
    }

    /**
     * Which of an act's {@code variants} lines this one is, 1-based.
     *
     * <p>Derived from the record and the position rather than drawn at random: the same line has to
     * come out the same way for every recipient, and a resumed conversation must not re-roll the
     * greeting it already said.
     */
    static int variantOf(UUID encounterId, int lineIndex, int variants) {
        return 1 + Math.floorMod(Objects.hash(encounterId, lineIndex), variants);
    }

    // ── the learn cascade ────────────────────────────────────────────────────────────────────

    /**
     * Who now knows {@code author}'s name. Three populations, three rules: the people being talked
     * to learn it outright; a bystanding agent learns it if it can see who spoke; a bystanding
     * player learns it only with line of sight, and otherwise gets a narration at most.
     */
    private static void spread(MinecraftServer server, Encounter e, LivingEntity writer,
            int radius, List<ServerPlayer> audience, AgentId author, String name) {
        for (AgentId them : e.participants()) {
            if (!them.equals(author)) {
                learn(server, them, author, name);
            }
        }
        BeingId who = BeingId.of(author);
        for (AgentBody body : AgentBodies.loaded(server)) {
            AgentId id = body.agentId();
            if (id == null || e.includes(id) || !near(body.entity(), writer, radius)
                    || !made(body, who)) {
                continue;
            }
            learn(server, id, body, author, name);
        }
        for (ServerPlayer player : audience) {
            AgentId id = ContactsSync.idOf(player);
            if (e.includes(id)) {
                continue; // a participant, already served above
            }
            if (player.hasLineOfSight(writer)) {
                // Narrated only on a name that was actually news: somebody who has known Alma for a
                // week does not need telling every time she introduces herself across the square.
                if (learn(server, id, null, author, name)) {
                    aside(player, narration("anima.social.overheard.seen", name));
                }
                continue;
            }
            // Heard through a wall: a voice with no face teaches nothing. The narration is worth
            // sending only when the OTHER party has a name to the listener, or it reads
            // "someone gave their name to someone".
            e.other(author)
                    .filter(addressee -> knows(server, player, addressee))
                    .ifPresent(addressee -> aside(player,
                            narration("anima.social.overheard.heard", nameOf(server, addressee))));
        }
    }

    /** {@code learner} now knows {@code whom}, its body resolved from the index. */
    private static boolean learn(MinecraftServer server, AgentId learner, AgentId whom, String name) {
        return learn(server, learner, AgentBodies.findLoaded(server, learner), whom, name);
    }

    /**
     * {@code learner} now knows {@code whom}, and whether that was NEWS. {@link ContactData#learn}
     * is the write path — it pays the company gauge — and everything after it hangs off its answer:
     * a name already in the book means the client shadow already carries it and the journal already
     * says so, so re-introduction would push a redundant entry and write "learned their name — Alma"
     * again every time Alma says it near somebody who has known her for a week.
     *
     * <p>The body is handed in rather than looked up, so a caller sweeping the loaded index does not
     * sweep it again per learner, and a player learner passes {@code null} rather than paying for a
     * scan that can only miss.
     */
    private static boolean learn(MinecraftServer server, AgentId learner, @Nullable AgentBody body,
            AgentId whom, String name) {
        if (!ContactData.get(server).learn(server, learner, whom)) {
            return false;
        }
        ContactsSync.learned(server, learner, whom);
        if (body != null) {
            body.journal().record(Category.BRAIN, "converse", "learned their name — " + name);
            // The percept's name is read out of this same book on the sensor's own attention
            // cadence, so a learner left to wait for it re-asks a name it was told seconds ago —
            // the per-record "already given here" guard cannot see across into a fresh record.
            body.beingSense().renamed(whom, name);
        }
        return true;
    }

    /** Whether this body has made {@code who} out as an individual — not merely heard something. */
    private static boolean made(AgentBody body, BeingId who) {
        for (Being being : body.beingSense().beings()) {
            if (being.id().equals(who) && being.identified() == Being.Identified.INDIVIDUAL) {
                return true;
            }
        }
        return false;
    }

    // ── the audience ─────────────────────────────────────────────────────────────────────────

    /** Every online player near enough to the writer to hear an ordinary speaking voice. */
    private static List<ServerPlayer> within(
            MinecraftServer server, LivingEntity writer, int radius) {
        List<ServerPlayer> heard = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (near(player, writer, radius)) {
                heard.add(player);
            }
        }
        return heard;
    }

    private static boolean near(Entity who, LivingEntity writer, int radius) {
        return who.level() == writer.level()
                && who.distanceToSqr(writer) <= (double) radius * radius;
    }

    /**
     * What this player may call {@code whom}: their name once earned, the stranger word until
     * then. The one place that decision is made — a second site composing it by hand is how a
     * name leaks (social foundations §4).
     */
    public static Component nameFor(MinecraftServer server, ServerPlayer player,
            @Nullable AgentId whom) {
        return knows(server, player, whom)
                ? Component.literal(nameOf(server, whom))
                : Component.translatable(STRANGER);
    }

    /**
     * What the directory calls this agent — or, for a player, what their account does: a player's
     * uuid is their agent id, and no directory ever holds one. {@code ?} where nothing can name it.
     */
    static String nameOf(MinecraftServer server, AgentId id) {
        return AgentDirectory.of(server).nameOf(id)
                .or(() -> Optional.ofNullable(server.getPlayerList().getPlayer(id.value()))
                        .map(online -> online.getName().getString()))
                .orElse("?");
    }

    /**
     * Whether this player may read {@code whom}'s name — their own book, or the whole directory if
     * they are watching from outside the fiction. One rule with the nameplates, or a creative
     * player reads a name over a head that chat denies them.
     *
     * <p>Another player's name is always readable: the vanilla nameplate has already told them, and
     * a "Someone" prefix on a line their friend just typed would be the UI contradicting itself.
     * That a PERSON must still ask (social foundations §8) is unaffected — this is what players
     * read, not what a settler knows.
     */
    private static boolean knows(MinecraftServer server, ServerPlayer player, @Nullable AgentId whom) {
        return whom != null && (ContactsSync.seesEveryone(player)
                || server.getPlayerList().getPlayer(whom.value()) != null
                || ContactData.get(server).knows(ContactsSync.idOf(player), whom));
    }

    /**
     * Tells this player something they noticed rather than something anybody said — on the
     * action bar, since chat carries nothing of Anima's any more (decision: Luiz, 2026-09-14).
     * The packet has the same shape on every live target, so no Stonecutter block.
     */
    public static void aside(ServerPlayer player, Component text) {
        player.connection.send(new ClientboundSetActionBarTextPacket(text));
    }

    private static Component narration(String key, String arg) {
        return Component.translatable(key, arg)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }
}
