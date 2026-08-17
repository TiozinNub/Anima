package dev.luizloyola.anima.mod.dash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.core.agent.need.Gauge;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * One frame of the dashboard, built on the server tick thread.
 *
 * <p><b>Everything that reads the world happens here</b>, inside the tick, and leaves as a String —
 * see {@link DashFeed} for why that is not negotiable.
 *
 * <p>The roster is the whole {@link AgentDirectory}: loaded, unloaded and dead alike, because the
 * question a radius cannot answer is "where did they go". An unloaded row carries only what the
 * directory and the records know and says so — it must read as <em>elsewhere</em>, never as
 * <em>gone</em>.
 *
 * <p>Detail is built only for the agents {@link DashWatch} says are expanded, and every section is
 * a readout {@code /anima} already prints. Nothing is invented here: a section that disagrees with
 * its command is a bug in one of the two.
 */
final class DashSnapshot {

    /** Journal lines per expanded agent — the tail that makes the last few seconds readable. */
    private static final int JOURNAL_TAIL = 24;

    private DashSnapshot() {
    }

    /** The whole frame. Runs on the tick thread; returns the text an HTTP thread will hand out. */
    static String render(MinecraftServer server, DashWatch watch) {
        JsonObject root = new JsonObject();
        root.addProperty("tick", server.getTickCount());
        root.add("players", players(server));
        root.addProperty("actingAs", watch.actingAs() == null ? null : watch.actingAs().toString());
        root.add("layers", layers(server, watch));

        ServerPlayer viewer = viewer(server, watch);
        Graves graves = Graves.get(server);
        AgentId pinned = viewer == null ? null : AgentSelection.pinned(viewer).orElse(null);

        JsonArray agents = new JsonArray();
        for (Map.Entry<AgentId, PrivateIdentity> known : AgentDirectory.of(server).known().entrySet()) {
            agents.add(row(server, known.getKey(), known.getValue(), graves, viewer, pinned, watch));
        }
        root.add("agents", agents);
        return root.toString();
    }

    /**
     * Who commands would run as. The watch's choice when it named one, otherwise the only player
     * online — with one player, which is the dev case, picking is noise.
     */
    private static @Nullable ServerPlayer viewer(MinecraftServer server, DashWatch watch) {
        UUID acting = watch.actingAs();
        if (acting != null) {
            return server.getPlayerList().getPlayer(acting);
        }
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        return online.size() == 1 ? online.get(0) : null;
    }

    private static JsonArray players(MinecraftServer server) {
        JsonArray out = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject row = new JsonObject();
            row.addProperty("uuid", player.getUUID().toString());
            row.addProperty("name", player.getName().getString());
            out.add(row);
        }
        return out;
    }

    /** The viewing player's debug layers, so the panel's toggles show what is actually on. */
    private static JsonArray layers(MinecraftServer server, DashWatch watch) {
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
     */
    private static JsonObject row(MinecraftServer server, AgentId id, PrivateIdentity identity,
            Graves graves, @Nullable ServerPlayer viewer, @Nullable AgentId pinned, DashWatch watch) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id.value().toString());
        row.addProperty("name", identity.name());
        row.addProperty("pinned", id.equals(pinned));

        AgentBody body = AgentBodies.findLoaded(server, id);
        boolean dead = graves.isDead(id);
        row.addProperty("state", dead ? "dead" : body == null ? "unloaded" : "loaded");

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
        // The head of the brain's own summary: one line is a roster cell, the rest is detail.
        List<String> brain = body.brain().describeLines();
        row.addProperty("brain", brain.isEmpty() ? "" : brain.get(0));
        if (viewer != null && viewer.level() == entity.level()) {
            row.addProperty("distance", Math.sqrt(viewer.distanceToSqr(entity)));
        }
        if (watch.isExpanded(id)) {
            row.add("detail", detail(server, body, brain));
        }
        return row;
    }

    /** Everything an expanded card shows — one key per section, each its own command's readout. */
    private static JsonObject detail(MinecraftServer server, AgentBody body, List<String> brain) {
        JsonObject out = new JsonObject();
        out.add("brain", strings(brain));
        out.add("needs", needs(body));
        out.add("nav", nav(body));
        out.add("peers", peers(body));
        out.add("knowledge", knowledge(server, body));
        out.add("journal", journal(body));
        out.add("inventory", inventory(body));
        return out;
    }

    /**
     * What the body wants. Written against the roster rather than a list of needs — it walks
     * {@code needs().all()}, so a gauge a consumer registers is drawn the day it exists.
     */
    private static JsonArray needs(AgentBody body) {
        JsonArray out = new JsonArray();
        for (Gauge gauge : body.needs().all()) {
            JsonObject row = new JsonObject();
            row.addProperty("key", gauge.kind().key());
            row.addProperty("value", gauge.value());
            row.addProperty("pressure", gauge.pressure());
            row.addProperty("says", gauge.describe());
            out.add(row);
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
