package dev.luizloyola.anima.mod.webdebug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.agent.need.Ramp;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.command.AgentSelection;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.debug.DebugView;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.identity.Graves;
import dev.luizloyola.anima.mod.nav.Navigator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * One frame of the dashboard, built on the server tick thread.
 *
 * <p><b>Everything that reads the world happens here</b>, inside the tick, and leaves as a String —
 * see {@link WebFeed} for why that is not negotiable.
 *
 * <p>The roster is the whole {@link AgentDirectory} rather than a radius, because the question a
 * radius cannot answer is "where did they go". An unloaded row carries only what the directory and
 * the records know and says so — it must read as <em>elsewhere</em>, never as <em>gone</em>.
 *
 * <p><b>The dead are counted, not built</b>, until {@link WebWatch#dead()} says the browser has
 * that section open. They are the one part of the roster that never shrinks — identity survives
 * death by decision — so a world's every grave was being rebuilt twenty times a second for a
 * section read once a session. The count itself goes out with every {@link WebClock#ROSTER} build,
 * the same clock the grave rows themselves are on — never {@link WebClock#SLOW}, or the footer's
 * census and the dead table could disagree by one for as long as the slower clock takes to catch
 * up. It is what the closed section, and the footer's census, actually read.
 *
 * <p>Detail is built only for the agents {@link WebWatch} says are expanded, and it is its own
 * section on its own {@link WebClock#DETAIL} clock now — a journal tail and a position have no
 * business sharing a rate. Every section is a readout {@code /anima} already prints: nothing is
 * invented here, and a section that disagrees with its command is a bug in one of the two.
 */
final class WebSnapshot {

    /** The rate the game runs at when nobody has told it otherwise. */
    private static final float VANILLA_TICKRATE = 20.0f;

    /** Journal lines per expanded agent — the tail that makes the last few seconds readable. */
    private static final int JOURNAL_TAIL = 24;

    private WebSnapshot() {
    }

    /**
     * The sections whose clocks fired, each rendered to the fragment the frame will carry.
     *
     * <p>Runs on the tick thread. A key absent from the returned map was not built and will not be
     * sent; a key mapped to <b>Java null</b> is one being dropped — see {@link WebModel}, where the
     * difference from the JSON null that {@code actingAs} carries is spelled out.
     */
    static Map<String, String> build(MinecraftServer server, WebWatch watch, Set<WebClock> due) {
        Map<String, String> out = new LinkedHashMap<>();
        if (due.contains(WebClock.HEALTH)) {
            out.put("health", health(server).toString());
        }
        if (due.contains(WebClock.CHART)) {
            // Null rather than an empty array: the key leaves the model outright, so a browser that
            // reopens the chart cannot be handed the five seconds it was closed for.
            out.put("samples", watch.ticks() ? samples(server).toString() : null);
        }
        if (due.contains(WebClock.SLOW)) {
            out.put("players", players(server).toString());
            out.put("layers", layers(server, watch).toString());
            UUID acting = watch.actingAs();
            out.put("actingAs", acting == null ? "null" : "\"" + acting + "\"");
        }
        if (due.contains(WebClock.ROSTER)) {
            out.put("agents", agents(server, watch).toString());
            // Same clock as the rows agents() builds, not SLOW's: split across two rates, the
            // footer's census and the dead table could disagree by one after a death.
            out.put("dead", Integer.toString(Graves.get(server).size()));
        }
        if (due.contains(WebClock.DETAIL)) {
            out.put("detail", details(server, watch).toString());
        }
        return out;
    }

    /**
     * The roster: every agent the directory knows, living, elsewhere or buried.
     *
     * <p><b>The loaded bodies are indexed once.</b> {@code AgentBodies.findLoaded} takes a lock on
     * the shared index and allocates a copy of every loaded body on each call, and this loop used to
     * make one call per known agent — fifty lock acquisitions and fifty array copies to answer fifty
     * lookups, sixty times a second.
     */
    private static JsonArray agents(MinecraftServer server, WebWatch watch) {
        Graves graves = Graves.get(server);
        ServerPlayer viewer = viewer(server, watch);
        AgentId pinned = viewer == null ? null : AgentSelection.pinned(viewer).orElse(null);
        AgentDirectory directory = AgentDirectory.of(server);
        Map<AgentId, AgentBody> loaded = loaded(server);

        JsonArray out = new JsonArray();
        for (Map.Entry<AgentId, PrivateIdentity> known : directory.known().entrySet()) {
            AgentId id = known.getKey();
            if (graves.isDead(id) && !watch.dead()) {
                continue;
            }
            out.add(row(directory, id, known.getValue(), graves, loaded.get(id), viewer, pinned));
        }
        return out;
    }

    /** Every expanded agent's sections, keyed by id — the object a card reads its own slice out of. */
    private static JsonObject details(MinecraftServer server, WebWatch watch) {
        Map<AgentId, AgentBody> loaded = loaded(server);
        JsonObject out = new JsonObject();
        for (AgentId id : watch.expanded()) {
            AgentBody body = loaded.get(id);
            if (body != null) {
                out.add(id.value().toString(), detail(server, body));
            }
        }
        return out;
    }

    /**
     * The loaded bodies by id, taken in one pass. See {@link #agents}.
     *
     * <p>Built once per section rather than once per frame, so a tick where the roster and the
     * detail clocks both fire builds it twice. That is two walks of the loaded list against the
     * <em>fifty locked copies</em> it replaces — not worth threading a parameter through for.
     */
    private static Map<AgentId, AgentBody> loaded(MinecraftServer server) {
        Map<AgentId, AgentBody> out = new HashMap<>();
        for (AgentBody body : AgentBodies.loaded(server)) {
            AgentId id = body.agentId();
            if (id != null) {
                out.put(id, body);
            }
        }
        return out;
    }

    /**
     * Who commands run as, and the point every distance is measured from — the watch's choice, and
     * nothing else.
     *
     * <p><b>No falling back to "the only player online".</b> That fallback made <em>nobody</em>
     * unsayable: the panel needs a choice that blanks every player-relative reading, and a server
     * that guesses would override it on the next frame. The browser guesses now, once, on the first
     * frame it sees a player — which also puts distance, pin and {@link #layers} on one answer to
     * "who is watching".
     */
    private static @Nullable ServerPlayer viewer(MinecraftServer server, WebWatch watch) {
        UUID acting = watch.actingAs();
        return acting == null ? null : server.getPlayerList().getPlayer(acting);
    }

    /**
     * How hard the server is finding it. The browser can time its own frames, but a tick's
     * <em>cost</em> leaves no trace on the wire, and cost is what says whether a slow world is
     * overloaded or merely paused.
     *
     * <p><b>{@code tps} is not a count</b>, it is {@link #achieved}: what the clock is managing,
     * which is the rate it was told to run at until the cost of a tick takes that away.
     *
     * <p><b>{@code rate} is what the clock was told to run at</b>, and beside {@code tps} it is the
     * half that says what a tick is <em>allowed</em> to cost: 1000/rate, which is the only honest
     * place to draw a budget. Fixed at 50ms it flatters a fast clock and slanders a slow one.
     * A sprinting server is told nothing — see {@link #achieved} — and {@code rate} still reports
     * the {@code /tick rate} underneath, because that is what it is: {@code mode} is what says the
     * clock is ignoring it.
     */
    private static JsonObject health(MinecraftServer server) {
        ServerTickRateManager clock = server.tickRateManager();
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        float wanted = clock.tickrate();

        JsonObject health = new JsonObject();
        health.addProperty("mspt", round(mspt));
        health.addProperty("tps",
                round(achieved(mspt, wanted, clock.isSprinting(), clock.isFrozen())));
        health.addProperty("rate", round(wanted));
        health.addProperty("mode", mode(clock));
        return health;
    }

    /**
     * Ticks a second the clock is actually managing: the rate it was told and the rate its ticks
     * cost, whichever is lower.
     *
     * <p><b>Headroom is not speed.</b> A server told 20 and spending 4ms a tick is at 20 and sleeps
     * the other 46; one spending 80ms is at 12.5, which is the number worth showing.
     *
     * <p><b>Sprinting takes the told half away</b>, which is the whole of what {@code /tick sprint}
     * does: no sleep, so the cost of a tick is the only thing left setting the rate. Clamped to the
     * configured 20 anyway — as this was — a world tearing through two thousand ticks a second
     * reported 20 and read as idle, which is the opposite of the truth.
     *
     * <p><b>A frozen world is at zero</b>, and the distinction that makes that true rather than
     * merely tidy is whose rate this is: the server thread keeps going at 20 a second under
     * {@code /tick freeze}, and the tick counter goes with it — the WORLD is what has stopped, and
     * the world's rate is what a dashboard is asked for. Vanilla's own {@code /tick query} says
     * "the game is frozen" and declines to name a rate at all.
     *
     * <p>An {@code mspt} of zero is a world whose ring has nothing in it yet, not an infinitely
     * fast one.
     *
     * @param mspt the average tick in milliseconds
     * @param wanted what {@code /tick rate} is set to
     */
    static double achieved(double mspt, float wanted, boolean sprinting, boolean frozen) {
        if (frozen) {
            return 0;
        }
        if (mspt <= 0) {
            return wanted;
        }
        double affordable = 1_000.0 / mspt;
        return sprinting ? affordable : Math.min(wanted, affordable);
    }

    /**
     * The last hundred ticks, oldest first, in milliseconds — the five seconds an average has
     * already thrown away, which is where a stutter actually lives.
     *
     * <p><b>Straightened out of the ring.</b> The server writes each tick's cost to
     * {@code tickCount % 100}, so that slot holds the NEWEST sample by the time this runs and the
     * one after it holds the oldest. Handed over raw, a chart would draw the last five seconds with
     * a seam through the middle of it, moving left every tick.
     *
     * <p>Only built while {@link WebClock#CHART} fires and {@link WebWatch#ticks()} is set — see
     * there for what it costs.
     */
    private static JsonArray samples(MinecraftServer server) {
        long[] ring = server.getTickTimesNanos();
        JsonArray out = new JsonArray(ring.length);
        int oldest = (server.getTickCount() + 1) % ring.length;
        for (int i = 0; i < ring.length; i++) {
            out.add(round(ring[(oldest + i) % ring.length] / 1_000_000.0));
        }
        return out;
    }

    /**
     * What the clock is <em>doing</em>, which no rate can say on its own: zero ticks a second is a
     * frozen world and a dead one in the same two characters, and a sprinting server is one
     * deliberately running flat out rather than one in trouble. Unsaid, every one of those reads as
     * a crash and sends whoever is debugging after the wrong thing.
     */
    private static String mode(ServerTickRateManager ticks) {
        if (ticks.isFrozen()) {
            return "frozen";
        }
        if (ticks.isSprinting()) {
            return "sprinting";
        }
        // Against vanilla's own 20 rather than a number of ours: `/tick rate` is what moves it, and
        // "slower than the game means to run" is the fact worth showing.
        float rate = ticks.tickrate();
        if (rate < VANILLA_TICKRATE) {
            return "slow";
        }
        return rate > VANILLA_TICKRATE ? "fast" : "normal";
    }

    /**
     * Two decimals, which is the whole useful precision of a tick cost — until it isn't.
     *
     * <p><b>A sprinting server ticks in microseconds</b>, where two decimals is the number zero:
     * the chart drew a flat line along the floor, the readout said a tick was free, and anything
     * dividing 1000 by it to get a rate divided by zero. Two significant digits instead, for the
     * values two decimals cannot hold at all. Everything above 0.005 is untouched.
     */
    static double round(double value) {
        double twoPlaces = Math.round(value * 100.0) / 100.0;
        if (twoPlaces != 0 || value == 0) {
            return twoPlaces;
        }
        return BigDecimal.valueOf(value).round(new MathContext(2)).doubleValue();
    }

    /**
     * Who is online — the reference points every distance below is measured from.
     *
     * <p>A face URL rather than a bare uuid because the picker that chooses one draws heads, and
     * {@link WebDebugger#faceUrl} is where the single host its CSP allows is named. Where they are
     * travels too: it is what tells an unmeasurable distance — another dimension — from a far one.
     */
    private static JsonArray players(MinecraftServer server) {
        JsonArray out = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject row = new JsonObject();
            row.addProperty("uuid", player.getUUID().toString());
            row.addProperty("name", player.getName().getString());
            row.addProperty("face", WebDebugger.faceUrl(player.getUUID()));
            row.addProperty("dimension", player.level().dimension().identifier().toString());
            row.add("pos", pos(player.blockPosition()));
            out.add(row);
        }
        return out;
    }

    /** The viewing player's debug layers, so the panel's toggles show what is actually on. */
    private static JsonArray layers(MinecraftServer server, WebWatch watch) {
        JsonArray out = new JsonArray();
        UUID acting = watch.actingAs();
        var on = acting == null ? java.util.EnumSet.noneOf(DebugLayer.class)
                : DebugView.layers(server, acting);
        for (DebugLayer layer : DebugLayer.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("key", layer.key());
            row.addProperty("on", on.contains(layer));
            out.add(row);
        }
        return out;
    }

    /**
     * One roster row. {@code state} is the fact everything else hangs off: only a LOADED agent has
     * a body to ask, so every live reading below is guarded by it rather than by a null check that
     * would read as "it has no navigator".
     *
     * <p><b>No detail.</b> It lives in its own section on its own clock now — a journal tail and a
     * position have no business sharing a rate.
     */
    private static JsonObject row(AgentDirectory directory, AgentId id, PrivateIdentity identity,
            Graves graves, @Nullable AgentBody body, @Nullable ServerPlayer viewer,
            @Nullable AgentId pinned) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id.value().toString());
        row.addProperty("name", identity.name());
        row.addProperty("pinned", id.equals(pinned));

        boolean dead = graves.isDead(id);
        row.addProperty("state", dead ? "dead" : body == null ? "unloaded" : "loaded");
        species(directory, id, body).ifPresent(species -> row.addProperty("species", species));

        if (dead) {
            graves.deathOf(id).ifPresent(death -> {
                row.addProperty("where", death.where());
                row.addProperty("cause", death.cause());
                row.addProperty("diedAtTick", death.diedAtTick());
            });
            return row;
        }
        if (body == null) {
            return row; // the directory knows the name and nothing else; that IS the row
        }

        LivingEntity entity = body.entity();
        BlockPos at = entity.blockPosition();
        row.addProperty("dimension", entity.level().dimension().identifier().toString());
        row.add("pos", pos(at));
        row.addProperty("health", entity.getHealth());
        row.addProperty("maxHealth", entity.getMaxHealth());
        row.addProperty("nav", body.navigator().describe());
        // The head alone: reaching it through describeLines() built every pressure line and the
        // whole task chain, per loaded agent, per frame, and threw them away.
        row.addProperty("brain", body.brain().describeHead());
        if (viewer != null && viewer.level() == entity.level()) {
            row.addProperty("distance", Math.sqrt(viewer.distanceToSqr(entity)));
        }
        return row;
    }

    /**
     * What kind of body this is, asked of the directory first.
     *
     * <p>The body is the FALLBACK, not the source: it answers only for the loaded, which would
     * leave the column blank on exactly the rows — unloaded, dead — where "what even is that" is
     * the hardest question. A consumer that has not taught its directory
     * {@link AgentDirectory#speciesOf} still gets its loaded rows labelled this way.
     *
     * <p>Empty rather than {@code ""} when neither answers: the key is then absent from the frame,
     * and a missing species reads as unknown instead of as a species called nothing.
     */
    private static Optional<String> species(AgentDirectory directory, AgentId id,
            @Nullable AgentBody body) {
        Optional<String> known = directory.speciesOf(id);
        if (known.isPresent()) {
            return known;
        }
        return body == null ? Optional.empty() : Optional.ofNullable(body.profile().species());
    }

    /** Everything an expanded card shows — one key per section, each its own command's readout. */
    private static JsonObject detail(MinecraftServer server, AgentBody body) {
        JsonObject out = new JsonObject();
        out.add("brain", strings(body.brain().describeLines()));
        out.add("needs", needs(body));
        out.add("nav", nav(body));
        out.add("peers", peers(body));
        out.add("knowledge", knowledge(server, body));
        out.add("journal", journal(body));
        out.add("inventory", inventory(body));
        return out;
    }

    /**
     * What the body wants, and the shape of the ramp it wants it on. Written against the roster
     * rather than a list of needs — it walks {@code needs().all()} and asks each gauge's own
     * {@link NeedKind} for its knees, so a gauge a consumer registers is drawn, ticks and all, the
     * day it exists.
     *
     * <p><b>{@code min} and {@code max} are the drawable span, not the axis</b> — see
     * {@link Ramp#floor()} and {@link Ramp#top}. A bar drawn on breath's declared 0..1200 would
     * show a settler's full lungs as a quarter of a track that never moves again.
     *
     * <p>The numbers are this body's, read live: two species with the same needs draw different
     * knees, and a retune moves them under a browser that is already watching.
     */
    private static JsonArray needs(AgentBody body) {
        JsonArray out = new JsonArray();
        AgentProfile profile = body.profile();
        for (Gauge gauge : body.needs().all()) {
            NeedKind kind = gauge.kind();
            JsonObject row = new JsonObject();
            row.addProperty("key", kind.key());
            row.addProperty("value", gauge.value());
            row.addProperty("pressure", gauge.pressure());
            row.addProperty("says", gauge.describe());
            row.addProperty("unit", kind.unit());
            row.addProperty("severity", gauge.severity().name().toLowerCase(Locale.ROOT));
            NeedLevel level = gauge.level();
            if (level != null) {
                row.addProperty("level", level.key());
            }
            Ramp ramp = kind.ramp();
            if (ramp != null) {
                row.addProperty("min", ramp.floor());
                row.addProperty("max", ramp.top(profile));
                row.addProperty("side", ramp.side(profile, gauge.value()));
                row.add("levels", knees(ramp, profile));
            }
            out.add(row);
        }
        return out;
    }

    /**
     * A need's knees, ascending — where each band starts, what it bids there, and what it will
     * spend to get it. Ascending rather than in declaration order because a readout draws them
     * along an axis, and config is free to reorder them.
     */
    private static JsonArray knees(Ramp ramp, AgentProfile profile) {
        List<NeedLevel> sorted = new ArrayList<>(ramp.levels());
        sorted.sort(Comparator.comparingDouble(level -> level.value(profile)));
        JsonArray out = new JsonArray();
        for (NeedLevel level : sorted) {
            JsonObject knee = new JsonObject();
            knee.addProperty("key", level.key());
            knee.addProperty("at", level.value(profile));
            knee.addProperty("pressure", level.pressure(profile));
            knee.addProperty("tolerance", level.tolerance(profile));
            out.add(knee);
        }
        return out;
    }

    /** The navigator's own numbers, not a recomputed copy — those agree until the tick it matters. */
    private static JsonObject nav(AgentBody body) {
        Navigator navigator = body.navigator();
        JsonObject out = new JsonObject();
        out.addProperty("state", navigator.state().name());
        out.addProperty("says", navigator.describe());
        BlockPos goal = navigator.goal();
        if (goal != null) {
            out.add("goal", pos(goal));
        }
        out.addProperty("stuckTicks", navigator.stuckTicks());
        out.addProperty("stuckLimit", navigator.stuckLimit());
        out.addProperty("noMoveTicks", navigator.noMoveTicks());
        out.addProperty("noMoveLimit", navigator.noMoveLimit());
        out.addProperty("repathsLeft", navigator.repathsLeft());
        out.addProperty("maxRepaths", navigator.maxRepaths());
        out.addProperty("careful", navigator.careful());
        var path = navigator.path();
        out.addProperty("waypoints", path == null ? 0 : path.waypoints().size());
        out.addProperty("index", navigator.pathIndex());
        return out;
    }

    /**
     * Through the BRAIN's own eyes ({@code percepts()}), not a fresh sensor: the cache carries the
     * movement history and the linger window, and a throwaway scan reports everyone as freshly seen
     * and standing still.
     */
    private static JsonArray peers(AgentBody body) {
        String pronoun = body.pronouns().object();
        JsonArray out = new JsonArray();
        for (Being being : body.brain().percepts().beings()) {
            JsonObject row = new JsonObject();
            row.addProperty("knownAs", being.knownAs());
            row.addProperty("says", being.tell(pronoun));
            row.addProperty("distance", being.distance());
            row.addProperty("awareness", being.awareness().name());
            out.add(row);
        }
        return out;
    }

    /** Counts per kind rather than every belief: the map is the MEMORY debug layer's job. */
    private static JsonArray knowledge(MinecraftServer server, AgentBody body) {
        JsonArray out = new JsonArray();
        AgentId id = body.agentId();
        if (id == null) {
            return out;
        }
        AgentKnowledge knowledge = Knowledges.of(server).forPerson(id);
        for (PoiKind kind : PoiKind.all()) {
            int known = knowledge.all(kind).size();
            int glimpsed = knowledge.glimpses(kind).size();
            if (known == 0 && glimpsed == 0) {
                continue; // a kind they have never met is not a row worth a line
            }
            JsonObject row = new JsonObject();
            row.addProperty("kind", kind.key());
            row.addProperty("known", known);
            row.addProperty("glimpsed", glimpsed);
            out.add(row);
        }
        return out;
    }

    private static JsonArray journal(AgentBody body) {
        JsonArray out = new JsonArray();
        for (Entry entry : body.journal().recent(JOURNAL_TAIL)) {
            JsonObject row = new JsonObject();
            row.addProperty("tick", entry.tick());
            row.addProperty("category", entry.category().name());
            row.addProperty("event", entry.event());
            row.addProperty("detail", entry.detail());
            out.add(row);
        }
        return out;
    }

    /** Occupied slots only — a full grid of "air" is forty rows saying nothing. What {@code inv list} prints. */
    private static JsonArray inventory(AgentBody body) {
        JsonArray out = new JsonArray();
        for (Inventory.Entry entry : body.inventory().occupied()) {
            JsonObject row = new JsonObject();
            row.addProperty("slot", entry.slot());
            row.addProperty("item", entry.stack().id());
            row.addProperty("count", entry.stack().count());
            out.add(row);
        }
        return out;
    }

    private static JsonArray strings(List<String> lines) {
        JsonArray out = new JsonArray();
        lines.forEach(out::add);
        return out;
    }

    private static JsonArray pos(BlockPos at) {
        JsonArray out = new JsonArray();
        out.add(at.getX());
        out.add(at.getY());
        out.add(at.getZ());
        return out;
    }
}
