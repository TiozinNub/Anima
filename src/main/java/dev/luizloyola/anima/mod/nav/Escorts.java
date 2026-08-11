package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * "Follow me" — the standing order that keeps an agent walking after somebody until it is called
 * off. A debug leash: a way to <em>lead</em> a body to the scene you want to watch it in.
 *
 * <p>Cheats at perception deliberately: the target's real position every few ticks — no line of
 * sight, no being sense, no memory. The real version is a drive over {@code Percepts.beings()}
 * beside {@code WanderInstinct}; this stays the operator's override of it.
 *
 * <p>Drives the {@link Navigator} directly, below the brain — the command that installs one
 * switches autonomy off first, since two owners of the legs is the bug. Every look checks the
 * navigator's goal is still the one <em>it</em> set; anything else means somebody drove, and the
 * order ends rather than fighting for it.
 *
 * <p>{@code near}/{@code far} are a hysteresis pair, not a range (see {@link Order}): {@code far}
 * is how far the target may get before the body sets off, {@code near} where it aims to end up,
 * measured out from the target. One number for both would start and stop the body on every look.
 *
 * <p>Transient, one map per server, gone on stop; not persisted.
 */
public final class Escorts {

    private Escorts() {
    }

    /**
     * How often an escort looks. Four times a second: no rubber-banding behind a walking player,
     * and the re-path it can trigger (a fresh {@code WorldSnapshot}, off-thread but not free)
     * fires at most every five ticks per following agent.
     */
    private static final int LOOK_INTERVAL_TICKS = 5;

    /** Where an order aims to end up when nobody said — close enough to be together. */
    public static final double DEFAULT_NEAR = 3.0;

    /** How much leash an order gives when nobody said. */
    public static final double DEFAULT_FAR = 5.0;

    /**
     * How far the target may drift from the goal already being walked to before the route is worth
     * recomputing.
     */
    private static final double DRIFT_REPATH = 3.0;

    /** Beyond this, hurry — the escort has fallen behind rather than merely trailing. */
    private static final double SPRINT_BEYOND = 12.0;

    /**
     * The slowest an escort may retry while its searches keep failing. A body walled off from its
     * target fails the same way from the same spot every time, and two seconds between attempts is
     * the difference between a quiet wait and a pathfinder worker pinned on a hopeless goal. Not
     * "give up": the target moves, so the next search is a different question.
     */
    private static final int FAIL_BACKOFF_TICKS = 40;

    /** Standing orders, per server: who is following, and everything the escort remembers. */
    private static final Map<MinecraftServer, Map<AgentId, Escort>> ORDERS = new HashMap<>();

    /** Idempotent, so a consumer that calls it too is harmless — see {@code CellOverlays.init}. */
    private static boolean registered;

    /**
     * A standing order as anyone outside can read it.
     *
     * @param target what is being followed — a player, usually, but any entity is allowed
     * @param near where the body aims to end up, measured out from the target: the goal cell is
     *     chosen this far from it, on the side the body is already on. Zero means the target's own
     *     cell — walk right onto them
     * @param far the leash: how far the target may get before a standing body sets off again.
     *     Never below {@code near}, or the body would arrive and immediately be told to set off
     */
    public record Order(UUID target, double near, double far) {
    }

    /** One standing order. Mutable: the escort's own memory of what it last told the legs. */
    private static final class Escort {
        private final Order order;
        /** Who asked, so the escort can say when it ends. Null when the console asked. */
        private final @Nullable UUID issuer;
        /**
         * The goal this escort last handed the navigator, or null while heeling. The navigator's
         * goal must still equal it, or the escort has been driven over and stands down.
         */
        private @Nullable BlockPos issued;
        /** Server tick before which no new search is requested — see {@link #FAIL_BACKOFF_TICKS}. */
        private int quietUntil;

        private Escort(Order order, @Nullable UUID issuer) {
            this.order = order;
            this.issuer = issuer;
        }
    }

    /** Call once from mod init. */
    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        ServerLifecycleEvents.SERVER_STOPPING.register(ORDERS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % LOOK_INTERVAL_TICKS == 0) {
                look(server);
            }
        });
    }

    /**
     * Installs (or replaces) the order that {@code agent} follows {@code target}. The caller is
     * expected to have taken the legs first — switched autonomy off and cancelled whatever task
     * was running — because this does not, and an escort that has to share the navigator ends
     * itself on its first look.
     *
     * @param near how close to end up, and {@code far} how much leash to give — see {@link Order}
     * @param issuer the player to tell when the order ends on its own; null from the console
     */
    public static void follow(MinecraftServer server, AgentId agent, Entity target,
            double near, double far, @Nullable UUID issuer) {
        Order order = new Order(target.getUUID(), near, Math.max(near, far));
        ORDERS.computeIfAbsent(server, s -> new LinkedHashMap<>())
                .put(agent, new Escort(order, issuer));
        AgentBody body = AgentBodies.findLoaded(server, agent);
        if (body != null) {
            // Halt whatever walk is in progress: a fresh order believes it has issued nothing, so a
            // navigator still carrying somebody else's goal would read as a hijack on the first look
            // and end the order before it walked a step.
            body.navigator().stop();
            body.journal().record(Category.BRAIN, "follow", target.getName().getString());
        }
    }

    /**
     * Calls the order off and halts the legs it was driving; false when there was no order. The
     * navigator is stopped rather than left mid-stride, because the walk in progress is the
     * escort's own and nobody else is going to claim it.
     */
    public static boolean stop(MinecraftServer server, AgentId agent) {
        Map<AgentId, Escort> orders = ORDERS.get(server);
        Escort order = orders == null ? null : orders.remove(agent);
        if (order == null) {
            return false;
        }
        AgentBody body = AgentBodies.findLoaded(server, agent);
        if (body != null) {
            body.navigator().stop();
            body.journal().record(Category.BRAIN, "follow", "called off");
        }
        return true;
    }

    public static @Nullable Entity following(MinecraftServer server, AgentId agent) {
        Map<AgentId, Escort> orders = ORDERS.get(server);
        Escort escort = orders == null ? null : orders.get(agent);
        return escort == null ? null : target(server, escort.order.target());
    }

    /** Every standing order, in the order they were given. */
    public static Map<AgentId, Order> all(MinecraftServer server) {
        Map<AgentId, Escort> orders = ORDERS.get(server);
        if (orders == null || orders.isEmpty()) {
            return Map.of();
        }
        Map<AgentId, Order> copy = new LinkedHashMap<>();
        orders.forEach((agent, escort) -> copy.put(agent, escort.order));
        return copy;
    }

    /** One cadence tick: every standing order re-examined, and the walk re-aimed if it needs it. */
    private static void look(MinecraftServer server) {
        Map<AgentId, Escort> orders = ORDERS.get(server);
        if (orders == null || orders.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<AgentId, Escort>> each = orders.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<AgentId, Escort> entry = each.next();
            String ended = examine(server, entry.getKey(), entry.getValue());
            if (ended != null) {
                each.remove();
            }
        }
    }

    /**
     * One order. Returns null to keep it, or the reason it is over — reported to whoever asked for
     * it, because an escort that quietly stopped following looks exactly like one that never
     * started.
     */
    private static @Nullable String examine(MinecraftServer server, AgentId agent, Escort escort) {
        AgentBody body = AgentBodies.findLoaded(server, agent);
        if (body == null) {
            // Unloaded, not gone: the order waits, since a body that comes back on a chunk load is
            // the same body.
            return null;
        }
        if (!body.entity().isAlive()) {
            return end(server, body, escort, "died", false);
        }
        Entity target = target(server, escort.order.target());
        if (target == null || !target.isAlive()) {
            return end(server, body, escort, "lost them", true);
        }
        if (target.level() != body.entity().level()) {
            // Hold, don't end: somebody nipping through a portal and back should find their escort
            // waiting where they left it, not standing down for the walk of it.
            return hold(body, escort);
        }

        Navigator navigator = body.navigator();
        if (!Objects.equals(navigator.goal(), escort.issued)) {
            // Somebody else drove — see the class doc. This is the one ending that must not stop
            // the navigator: the goal it is carrying belongs to whoever just took it, so halting
            // here would cancel the very order that displaced us.
            return end(server, body, escort, "someone else took the legs", false);
        }

        double near = escort.order.near();
        double far = escort.order.far();
        double distance = Math.sqrt(body.entity().distanceToSqr(target));
        boolean standing = escort.issued == null;
        if (standing) {
            // On the leash: nothing to do until the target is past the end of it.
            if (distance <= far) {
                return null;
            }
        } else if (distance <= near) {
            // Caught up on the way — near is where the walk was aimed, so being inside it means
            // the walk is done however it got there.
            return hold(body, escort);
        } else if (navigator.state() == Navigator.State.ARRIVED && distance <= far) {
            // Arrived where it was aimed. Inside `far` that ends the errand even if the target has
            // shifted since; beyond it, the next branch re-aims rather than standing where they were.
            return hold(body, escort);
        }
        if (server.getTickCount() < escort.quietUntil) {
            return null;
        }

        Navigator.State state = navigator.state();
        BlockPos want = goalNear(body, target, near);
        boolean settled = state == Navigator.State.IDLE
                || state == Navigator.State.ARRIVED
                || state == Navigator.State.FAILED;
        if (state == Navigator.State.FAILED) {
            escort.quietUntil = server.getTickCount() + FAIL_BACKOFF_TICKS;
        }
        boolean drifted = escort.issued != null
                && Math.sqrt(want.distSqr(escort.issued)) > DRIFT_REPATH;
        if (!standing && !settled && !drifted) {
            return null; // still walking somewhere near enough to where they are
        }
        body.navigateTo(Vec3.atBottomCenterOf(want),
                distance > SPRINT_BEYOND ? Gait.SPRINT : Gait.WALK);
        // Read the goal back off the navigator rather than assuming it took the one we passed —
        // the escort's whole claim to these legs is that the two agree.
        escort.issued = navigator.goal();
        return null;
    }

    /**
     * Where to walk to: the cell {@code near} blocks out from {@code target}, on the side the body
     * is already on, so a companion settles behind you rather than shouldering through you.
     * {@code near} of zero, and standing exactly on them, is the target's own cell.
     *
     * <p>Horizontal only, at the target's own height: pushing the goal up or down a slope would
     * hand the pathfinder a cell in the air or inside a hill. Standability is the search's question
     * — an unreachable goal comes back as a path to the nearest cell it could reach.
     */
    private static BlockPos goalNear(AgentBody body, Entity target, double near) {
        BlockPos on = target.blockPosition();
        if (near <= 0.0) {
            return on;
        }
        double dx = body.entity().getX() - target.getX();
        double dz = body.entity().getZ() - target.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0e-3) {
            return on;
        }
        return BlockPos.containing(
                target.getX() + dx / flat * near, target.getY(), target.getZ() + dz / flat * near);
    }

    /** Close enough (or nothing to walk toward): stand, and remember that we are standing. */
    private static @Nullable String hold(AgentBody body, Escort escort) {
        if (escort.issued != null) {
            body.navigator().stop();
            escort.issued = null;
        }
        return null;
    }

    /**
     * Ends an order: the journal says so, and whoever asked hears about it — an escort that
     * quietly stopped following looks exactly like one that never started.
     *
     * @param halt whether to stop the legs on the way out. True for every ending where the walk in
     *     progress is still the escort's own; false when it is somebody else's now, or when there
     *     is no body left to steer
     */
    private static String end(MinecraftServer server, AgentBody body, Escort escort, String why,
            boolean halt) {
        if (halt && body.entity().isAlive()) {
            body.navigator().stop();
        }
        body.journal().record(Category.BRAIN, "follow", "ended: " + why);
        ServerPlayer issuer = escort.issuer == null ? null
                : server.getPlayerList().getPlayer(escort.issuer);
        if (issuer != null) {
            issuer.sendSystemMessage(Component.literal(
                            body.entity().getName().getString() + " stopped following — " + why + ".")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return why;
    }

    /** The followed entity, wherever it is. Players first: that is the overwhelmingly common case. */
    private static @Nullable Entity target(MinecraftServer server, UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            return player;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
