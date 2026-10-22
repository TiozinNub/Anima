package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Recounting;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.log.Journals;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * {@code logs/anima/<run>/social.log}: every conversation as one transcript, plus what talking
 * did — names learned, items handed over, parties joined, a player's buttons and clicks
 * (docs/superpowers/specs/2026-10-22-social-log-design.md). A conversation has two sides, so it
 * is read from one file; each participant's journal gets only the {@link Category#SOCIAL} pointer
 * to it.
 *
 * <p>A line is {@code [tick] <encounter> <kind> <text>}, behind the sink's wall clock. The
 * encounter column is {@link Encounter#tag()}, or dashes for anything outside a record, so one
 * grep of a tag is the whole conversation.
 */
public final class SocialLog {

    public static final String FILE = "social.log";

    public static final String HAIL = "hail";
    public static final String OPEN = "open";
    public static final String BUSY = "busy";
    public static final String SAY = "say";
    public static final String SYSTEM = "system";
    public static final String CLOSE = "close";
    public static final String REAPED = "reaped";
    public static final String LEARN = "learn";
    public static final String GIVE = "give";
    public static final String JOIN = "join";
    public static final String LEAVE = "leave";
    public static final String LEAD = "lead";
    public static final String LEDGER = "ledger";
    public static final String TAP = "tap";
    public static final String MENU = "menu";
    public static final String CLICK = "click";
    public static final String NOTICE = "notice";
    /** A player's order to their party — follow me, here — written by the consumer that runs it. */
    public static final String ORDER = "order";
    /** A found place put to a leader, and the answer — written by the consumer that runs it. */
    public static final String HOME = "home";

    /** Every kind, for the test that pins the vocabulary. */
    static final List<String> KINDS = List.of(HAIL, OPEN, BUSY, SAY, SYSTEM, CLOSE, REAPED, LEARN,
            GIVE, JOIN, LEAVE, LEAD, LEDGER, TAP, MENU, CLICK, NOTICE, ORDER, HOME);

    private static final String NO_RECORD = "--------";
    private static final int KIND_WIDTH = 6;

    private SocialLog() {
    }

    /** Writes one line about {@code e}, or about no record when it is null. */
    public static void write(MinecraftServer server, @Nullable Encounter e, String kind, String text) {
        if (!Config.get().b(Knob.JOURNAL_SOCIAL_LOG)) {
            return;
        }
        Journals.appendRun(server, FILE,
                line(server.overworld().getGameTime(), e == null ? null : e.tag(), kind, text));
    }

    /** What the directory or the account calls {@code id}; {@code ?} when nothing can name it. */
    public static String name(MinecraftServer server, @Nullable AgentId id) {
        return id == null ? "?" : Speeches.nameOf(server, id);
    }

    static String line(long tick, @Nullable String tag, String kind, String text) {
        StringBuilder out = new StringBuilder()
                .append('[').append(tick).append("] ")
                .append(tag == null ? NO_RECORD : tag).append(' ').append(kind);
        out.append(" ".repeat(Math.max(1, KIND_WIDTH + 1 - kind.length())));
        return out.append(text).toString();
    }

    /** {@code body} reached out to {@code whom} — {@code how} says whether it shouted or walked. */
    public static void hailed(AgentBody body, BeingId whom, String how) {
        if (!(body.entity().level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        AgentId them = whom.asPerson();
        Entity target = Speeches.bodyOf(server, them);
        String distance = target == null || target.level() != level ? "out of sight"
                : "%.1f blocks".formatted(Math.sqrt(target.distanceToSqr(body.entity())));
        write(server, null, HAIL, name(server, body.agentId()) + " -> " + name(server, them) + ", " + how
                + ", " + distance);
    }

    // ── the record's own events, from Speeches' listener ────────────────────────────────────

    static void opened(MinecraftServer server, Encounter e, AgentId self, Speech.Opening opening) {
        List<String> named = new ArrayList<>();
        for (AgentId who : e.participants()) {
            named.add(name(server, who) + " (" + who.value().toString().substring(0, 8) + ")");
        }
        String how = switch (opening) {
            case I_HAILED -> name(server, self) + " hailed";
            case THEY_HAILED -> e.other(self).map(them -> name(server, them)).orElse("?") + " hailed";
            case QUIET -> "opened quietly by " + name(server, self);
        };
        write(server, e, OPEN, String.join(" + ", named) + ", " + how);
        for (AgentId who : e.participants()) {
            String others = String.join(", ", othersOf(server, e, who));
            Journals.of(server).record(who, Category.SOCIAL, OPEN,
                    e.tag() + " with " + others + ", " + how);
        }
    }

    static void busy(MinecraftServer server, AgentId self, AgentId other) {
        EncounterData data = EncounterData.get(server);
        String why = data.roster().openFor(other)
                .map(theirs -> name(server, other) + " is talking with "
                        + String.join(", ", othersOf(server, theirs, other)) + " (" + theirs.tag() + ")")
                .or(() -> data.roster().openFor(self).map(mine -> name(server, self)
                        + " is still in " + mine.tag()))
                .orElse("one of them is talking");
        write(server, null, BUSY, name(server, self) + " -> " + name(server, other) + ": " + why);
    }

    static void said(MinecraftServer server, Encounter e, Utterance u, @Nullable String why) {
        if (u.system()) {
            write(server, e, SYSTEM, u.act() + payload(server, u.payload()));
            return;
        }
        StringBuilder text = new StringBuilder(name(server, u.author())).append(": ").append(u.act())
                .append(payload(server, u.payload()));
        SpeechAct act = SpeechActs.byKey(u.act()).orElse(null);
        if (act != null && Recounting.read(u.payload()).isEmpty()) {
            int index = e.transcript().size() - 1;
            String key = Speeches.renderKey(act, u.payload(), Speeches.variantOf(e.id(), index, act.variants()));
            if (!key.equals(act.langKey())) {
                text.append(" key=").append(key);
            }
        }
        if (why != null) {
            text.append(" | ").append(why);
        }
        write(server, e, SAY, text.toString());
    }

    static void closed(MinecraftServer server, Encounter e, String kind) {
        List<Utterance> lines = e.transcript();
        String ending = lines.isEmpty() ? "nothing said"
                : ended(server, lines.get(lines.size() - 1));
        String length = lines.size() + " lines over " + (e.closedAt() - e.openedAt()) + " ticks";
        write(server, e, kind, length + ", ended by " + ending);
        for (AgentId who : e.participants()) {
            Journals.of(server).record(who, Category.SOCIAL, CLOSE,
                    e.tag() + ", " + lines.size() + " lines, ended by " + ending);
        }
    }

    private static String ended(MinecraftServer server, Utterance last) {
        if (!last.system()) {
            return name(server, last.author()) + ": " + last.act();
        }
        String subject = last.payload().get(Utterance.SUBJECT);
        return last.act() + (subject == null ? "" : " (" + name(server, idOf(subject)) + ")");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * A payload as {@code  key=value} pairs in a stable order, with the {@code subject} of a system
     * line named instead of spelled as a uuid. Empty for an empty payload.
     */
    static String payload(MinecraftServer server, Map<String, String> payload) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(payload).entrySet()) {
            String value = entry.getKey().equals(Utterance.SUBJECT)
                    ? name(server, idOf(entry.getValue())) : entry.getValue();
            out.append(' ').append(entry.getKey()).append('=').append(value);
        }
        return out.toString();
    }

    private static List<String> othersOf(MinecraftServer server, Encounter e, AgentId self) {
        List<String> out = new ArrayList<>();
        for (AgentId who : e.participants()) {
            if (!who.equals(self)) {
                out.add(name(server, who));
            }
        }
        return out;
    }

    private static @Nullable AgentId idOf(String raw) {
        try {
            return AgentId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
