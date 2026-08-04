package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.knowledge.PoiSensorCore;
import dev.luizloyola.anima.core.brain.knowledge.ReadPool;
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
 * One {@link ReadPool} per server, and the warnings that tell an operator when it is about to
 * matter — {@link RayPools} for the other expensive channel, the same shape.
 *
 * <p><b>The projection is calculated, never measured.</b> Reads per tick is loaded agents times
 * what each asks for at rest, so this rides entity load and unload rather than counting at tick
 * time.
 *
 * <p>Two warnings: <b>projected</b> is "this many agents would ask for more than the ceiling
 * allows", a configuration observation; <b>cancelling</b> is "the ceiling actually refused
 * somebody", degradation now, which can happen well below the projection line. Both are
 * edge-triggered with hysteresis, because level-triggered on a busy server is twenty lines a
 * second.
 */
public final class ReadPools {

    /** Fraction of the ceiling the projection warns at. */
    private static final double WARN_AT = 0.90;
    /** And drops back below before it will warn again — the anti-flapping gap. */
    private static final double REARM_AT = 0.75;
    /** How often the refused-anybody flag is sampled. Five seconds; it is one boolean read. */
    private static final int CANCELLING_SAMPLE_TICKS = 100;

    private static final Map<MinecraftServer, ReadPool> POOLS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Boolean> WARNED = new WeakHashMap<>();
    private static final Map<MinecraftServer, Boolean> CANCELLING = new WeakHashMap<>();

    private ReadPools() {
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
        // The "is it refusing anybody" edge cannot ride a population change: a wood does not move
        // the population, and walking into one is exactly when this bites. Sampled on a slow beat.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % CANCELLING_SAMPLE_TICKS == 0) {
                synchronized (POOLS) {
                    ReadPool pool = POOLS.get(server);
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
        // should say so at once rather than waiting for the next agent to wander into a chunk.
        Config.store().onInstall(ReadPools::reprojectAll);
    }

    /** The pool this server's agents share, created on first use. */
    public static ReadPool of(MinecraftServer server) {
        synchronized (POOLS) {
            return POOLS.computeIfAbsent(server,
                    key -> new ReadPool(() -> Config.get().i(Knob.READS_PER_TICK_TOTAL)));
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
        ReadPool pool = of(server);
        pool.population(AgentBodies.snapshot(server).size());
        project(server, pool);
    }

    /** Re-runs the projection for every live server — what a config reload triggers. */
    private static void reprojectAll() {
        synchronized (POOLS) {
            POOLS.forEach(ReadPools::project);
        }
    }

    private static void project(MinecraftServer server, ReadPool pool) {
        int ceiling = Config.get().i(Knob.READS_PER_TICK_TOTAL);
        if (ceiling <= 0) {
            return; // no ceiling configured: nothing to project against
        }
        int projected = pool.population() * PoiSensorCore.readsPerTick();
        boolean warned = Boolean.TRUE.equals(WARNED.get(server));

        if (!warned && projected >= ceiling * WARN_AT) {
            WARNED.put(server, true);
            AnimaMod.LOGGER.warn(
                    "read budget: {} agents would ask for ~{} block reads a tick against a ceiling"
                            + " of {} ({}). They will notice places later rather than not at all,"
                            + " and the skyline gives way before the ground at their feet does —"
                            + " raise limits.reads_per_tick_total, lower limits.reads_per_tick, or"
                            + " keep fewer agents loaded.",
                    pool.population(), projected, ceiling, Knob.READS_PER_TICK_TOTAL.key());
        } else if (warned && projected < ceiling * REARM_AT) {
            WARNED.put(server, false);
            AnimaMod.LOGGER.info("read budget: back under the line ({} agents, ~{} of {} reads).",
                    pool.population(), projected, ceiling);
        }

        watchCancelling(server, pool);
    }

    /** The other edge: not "would ask for too much" but "was actually refused". */
    private static void watchCancelling(MinecraftServer server, ReadPool pool) {
        boolean cancelling = pool.cancelling();
        pool.clearCancelling();
        boolean reported = Boolean.TRUE.equals(CANCELLING.get(server));
        if (cancelling != reported) {
            CANCELLING.put(server, cancelling);
            if (cancelling) {
                AnimaMod.LOGGER.warn("read budget: the ceiling is now holding block reads back — "
                        + "agents are noticing places a tick or two late.");
            } else {
                AnimaMod.LOGGER.info("read budget: no longer holding block reads back.");
            }
        }
    }
}
