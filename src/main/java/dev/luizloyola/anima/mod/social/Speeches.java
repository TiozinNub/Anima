package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.net.ContactsSync;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Where a spoken line becomes something a player reads — the one choke point between the speech
 * machinery and chat.
 *
 * <p><b>Composed per recipient.</b> Whoever knows that agent reads a name; everybody else reads a
 * stranger. So there is no single rendered line to broadcast: each nearby player gets their own,
 * built from their own contact book.
 *
 * <p><b>The writer is not the speaker.</b> {@code speaker} is the body whose engine wrote the line
 * — it supplies the position, the chat radius and so the audience. WHO is shown comes from
 * {@link Utterance#author()}, and the two differ on the answerer's prefill of a hail it did not
 * make (see {@code SpeechEngine.join}). Resolve a name or a portrait from the author, never from
 * the writer.
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
     * Puts {@code u} in front of everyone near enough to hear it, and hands out the name it gave
     * away. Called once as the line lands — never by replaying a transcript, which would re-teach
     * a name to a room that has since forgotten it on purpose.
     */
    public static void deliver(MinecraftServer server, AgentBody speaker, Encounter e, Utterance u) {
        SpeechAct act = SpeechActs.byKey(u.act()).orElse(null);
        if (act == null) {
            // A word this install does not carry: silence beats a raw lang key in chat. System
            // lines are NOT skipped — "(the conversation trailed off)" is the fiction, not debug.
            return;
        }
        LivingEntity writer = speaker.entity();
        int radius = speaker.profile().i(ProfileAspect.SOCIAL_CHAT_RADIUS);
        List<ServerPlayer> audience = within(server, writer, radius);
        AgentId author = u.author();

        String spoken = author == null ? "" : nameOf(server, author);
        String rendered = renderKey(act, u.payload(),
                variantOf(e.id(), e.transcript().size() - 1, act.variants()));
        Optional<Component> portrait =
                author == null ? Optional.empty() : Portraits.of(server, author);

        for (ServerPlayer player : audience) {
            player.sendSystemMessage(u.system()
                    ? Component.translatable(rendered)
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
                    : said(server, player, author, spoken, portrait, act, rendered));
        }

        if (author != null && act.introduces()) {
            spread(server, e, writer, radius, audience, author, spoken);
        }
    }

    // ── the line ─────────────────────────────────────────────────────────────────────────────

    /** One recipient's copy: {@code [portrait] Name: line}, with the name they have earned. */
    private static Component said(MinecraftServer server, ServerPlayer player,
            AgentId author, String spoken, Optional<Component> portrait,
            SpeechAct act, String rendered) {
        MutableComponent line = Component.empty();
        portrait.ifPresent(face -> line.append(face).append(" "));
        line.append(knows(server, player, author)
                ? Component.literal(spoken)
                : Component.translatable(STRANGER));
        line.append(": ");
        // An introducing act says the name out loud, so the line itself carries it as an argument
        // even for a listener whose prefix still reads "Someone" — that IS the introduction.
        return line.append(act.introduces()
                ? Component.translatable(rendered, spoken)
                : Component.translatable(rendered));
    }

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
                    player.sendSystemMessage(aside("anima.social.overheard.seen", name));
                }
                continue;
            }
            // Heard through a wall: a voice with no face teaches nothing. The narration is worth
            // sending only when the OTHER party has a name to the listener, or it reads
            // "someone gave their name to someone".
            e.other(author)
                    .filter(addressee -> knows(server, player, addressee))
                    .ifPresent(addressee -> player.sendSystemMessage(
                            aside("anima.social.overheard.heard", nameOf(server, addressee))));
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

    /** What the directory calls this agent, or {@code ?} where nothing can name it. */
    private static String nameOf(MinecraftServer server, AgentId id) {
        return AgentDirectory.of(server).nameOf(id).orElse("?");
    }

    /**
     * Whether this player may read {@code whom}'s name — their own book, or the whole directory if
     * they are watching from outside the fiction. One rule with the nameplates, or a creative
     * player reads a name over a head that chat denies them.
     */
    private static boolean knows(MinecraftServer server, ServerPlayer player, @Nullable AgentId whom) {
        return whom != null && (ContactsSync.seesEveryone(player)
                || ContactData.get(server).knows(ContactsSync.idOf(player), whom));
    }

    /** A narration line: what the player noticed, not what anybody said. */
    private static Component aside(String key, String arg) {
        return Component.translatable(key, arg)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }
}
