package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.sense.BeingSensorCore;
import dev.luizloyola.anima.core.brain.sense.RayPool;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * One {@link RayPool} per server, and the warnings that tell an operator when it is about to
 * matter.
 *
 * <p><b>The projection is calculated, never measured.</b> Rays per tick is loaded agents times
 * what each asks for at rest, so this rides entity load and unload rather than counting at tick
 * time.
 *
 * <p>Both warnings are edge-triggered with hysteresis: level-triggered on a struggling server is
 * twenty lines a second, and a population oscillating at a chunk border would log forever without
 * a rearm below the line. They say different things:
 *
 * <ul>
 *   <li><b>Projected</b> — this many agents would ask for more than the ceiling allows: a
 *       configuration observation.
 *   <li><b>Cancelling</b> — the ceiling actually refused somebody: degradation now, and it can
 *       happen below the projection line, so it is watched separately rather than inferred.
 * </ul>
 *
 * <p><b>Known limit, accepted:</b> agents times base is a floor, not a peak, the per-agent ask
 * being elastic in the backlog. It catches "you have too many agents", not "forty of them walked
 * into the same cave" — which is transient, and what the cancelling watch and deferral absorb.
 */
public final class RayPools {

    /** Fraction of the ceiling the projection warns at. */
    private static final double WARN_AT = 0.90;
    /** And drops back below before it will warn again — the anti-flapping gap. */
    private static final double REARM_AT = 0.75;
    /** How often the refused-anybody flag is sampled. Five seconds; it is one boolean read. */
    private static final int CANCELLING_SAMPLE_TICKS = 100;

    private static final Map<MinecraftServer, RayPool> POOLS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Boolean> WARNED = new WeakHashMap<>();
    private static final Map<MinecraftServer, Boolean> CANCELLING = new WeakHashMap<>();

    private RayPools() {
    }

    /** Subscribes the pools to population changes and to config reloads. Called once, from init. */
    public static void install() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AgentBody) {
                repopulate(level.getServer());
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof AgentBody) {
                repopulate(level.getServer()); // the pool's balances are weakly keyed; they go too
            }
        });
        // The "is it refusing anybody" edge cannot ride a population change: a cave full of mobs
        // moves the pool without moving the population. Sampled on a slow beat instead — without
        // it the stopped-cancelling edge would never fire on a stable server.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % CANCELLING_SAMPLE_TICKS == 0) {
                synchronized (POOLS) {
                    RayPool pool = POOLS.get(server);
                    if (pool != null) {
                        watchCancelling(server, pool);
                    }
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (POOLS) {
                POOLS.remove(server);
                WARNED.remove(server);
                CANCELLING.remove(server);
            }
        });
        // The projection is a function of the ceiling, and the ceiling is reloadable. Lowering it
        // should say so at once rather than waiting for the next entity to wander into a chunk.
        Config.store().onInstall(RayPools::reprojectAll);
    }

    /** The pool this server's agents share, created on first use. */
    public static RayPool of(MinecraftServer server) {
        synchronized (POOLS) {
            return POOLS.computeIfAbsent(server,
                    key -> new RayPool(() -> Config.get().i(Knob.RAYS_PER_TICK)));
        }
    }

    /**
     * Recounts the loaded agents and re-runs both edge checks. Cheap, and only ever called when
     * something actually changed.
     */
    public static void repopulate(MinecraftServer server) {
        if (server == null) {
            return;
        }
        RayPool pool = of(server);
        pool.population(AgentBodies.snapshot(server).size());
        project(server, pool);
    }

    /** Re-runs the projection for every live server — what a config reload triggers. */
    private static void reprojectAll() {
        synchronized (POOLS) {
            POOLS.forEach(RayPools::project);
        }
    }

    private static void project(MinecraftServer server, RayPool pool) {
        int ceiling = Config.get().i(Knob.RAYS_PER_TICK);
        int projected = pool.population() * BeingSensorCore.rayBudgetBase();
        boolean warned = Boolean.TRUE.equals(WARNED.get(server));

        if (!warned && projected >= ceiling * WARN_AT) {
            WARNED.put(server, true);
            AnimaMod.LOGGER.warn(
                    "sight budget: {} agents would ask for ~{} rays a tick against a ceiling of {}"
                            + " ({}). They will notice things later rather than not at all — raise"
                            + " limits.rays_per_tick, lower limits.ray_budget, or keep fewer agents"
                            + " loaded.",
                    pool.population(), projected, ceiling, Knob.RAYS_PER_TICK.key());
        } else if (warned && projected < ceiling * REARM_AT) {
            WARNED.put(server, false);
            AnimaMod.LOGGER.info("sight budget: back under the line ({} agents, ~{} of {} rays).",
                    pool.population(), projected, ceiling);
        }

        watchCancelling(server, pool);
    }

    /** The other edge: not "would ask for too much" but "was actually refused". */
    private static void watchCancelling(MinecraftServer server, RayPool pool) {
        boolean cancelling = pool.cancelling();
        pool.clearCancelling();
        boolean reported = Boolean.TRUE.equals(CANCELLING.get(server));
        if (cancelling != reported) {
            CANCELLING.put(server, cancelling);
            if (cancelling) {
                AnimaMod.LOGGER.warn("sight budget: the ceiling is now deferring sight checks — "
                        + "agents are noticing things a tick or two late.");
            } else {
                AnimaMod.LOGGER.info("sight budget: no longer deferring sight checks.");
            }
        }
    }
}
