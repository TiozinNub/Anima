package dev.luizloyola.anima.mod.log;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.mod.brain.BeingSense;
import dev.luizloyola.anima.mod.brain.PoiSensor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * The {@code mod} home of the per-person debug log: one pure {@link JournalService} per running
 * server, given the two things a {@code core} service cannot have — a real game-time clock and a
 * place in the server lifecycle. Server-scoped and identity-keyed like {@code PersonDirectory},
 * transient and rebuilt each boot like {@code PathfinderService}.
 *
 * <p><b>Not persisted</b>: no {@code SavedData}, because the rings are ephemeral by design — the
 * durable archive is the per-person file {@link JournalFileSink} writes — so a fresh boot starts
 * with empty rings.
 *
 * <p>The clock is the overworld's game time
 * ({@link net.minecraft.server.level.ServerLevel#getGameTime()}), shared server-wide, so every
 * person's lines share one monotonic timeline. A periodic {@link JournalService#sweep()} enforces
 * the age bound; the per-person line cap enforces itself on every write.
 */
public final class Journals {
    private Journals() {}

    /** How often the age sweep runs — ~30s at 20 ticks/second. The line cap needs no cadence. */
    private static final int SWEEP_INTERVAL_TICKS = 600;

    /** One service per live server; removed on stop. Server-thread only, like the map it mirrors. */
    private static final Map<MinecraftServer, JournalService> SERVICES = new HashMap<>();

    /** The file sink attached to each server's service, so {@code SERVER_STOPPING} can flush + close it. */
    private static final Map<MinecraftServer, JournalFileSink> SINKS = new HashMap<>();

    /** Call once from mod init: ties the per-server services, their file sinks, and the age sweep to
     *  the lifecycle. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            JournalFileSink sink = SINKS.remove(server);
            if (sink != null) {
                sink.close(); // final drain + close every per-person file
            }
        });
        // The RINGS outlive STOPPING and go at STOPPED, and the order is load-bearing: entities are
        // written to their chunks between the two, and a body saves its own journal as it goes.
        // Removing the service at STOPPING truncated every Person's saved ring — nineteen lines
        // became five.
        ServerLifecycleEvents.SERVER_STOPPED.register(SERVICES::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (// Game time, not the server's tick count: that counter restarts at zero every boot, so a
            // cadence keyed on it re-phases on every reload. The world's clock is saved with the world.
            server.overworld().getGameTime() % SWEEP_INTERVAL_TICKS == 0) {
                JournalService service = SERVICES.get(server);
                if (service != null) {
                    service.sweep();
                }
            }
        });
        // The muted set is derived from two knobs, not equal to either one — same shape as
        // ReadPools' ceiling projection. Without this a running world only picks up a sense.*
        // toggle on its next restart, not on the /anima config set that just changed it.
        Config.store().onInstall(Journals::remuteAll);
    }

    /** What {@link #of} installs at boot, and what a config change re-installs into every live server. */
    private static Set<JournalService.Muted> mutedFrom() {
        Set<JournalService.Muted> muted = new HashSet<>();
        if (!Config.get().b(Knob.JOURNAL_SENSE_PEER)) {
            muted.add(new JournalService.Muted(Category.SENSE, BeingSense.EVENT_PEER));
        }
        if (!Config.get().b(Knob.JOURNAL_SENSE_OVERLOOKED)) {
            muted.add(new JournalService.Muted(Category.SENSE, PoiSensor.EVENT_OVERLOOKED));
        }
        return muted;
    }

    /** Re-applies the muted set to every live server's journal — what a config reload triggers. */
    private static void remuteAll() {
        Set<JournalService.Muted> muted = mutedFrom();
        for (JournalService service : SERVICES.values()) {
            service.mute(muted);
        }
    }

    /**
     * This server's journal service, created on first use with the overworld game-time clock and its
     * per-person file sink. Server-thread only, so the plain get-then-put needs no locking.
     */
    public static JournalService of(MinecraftServer server) {
        JournalService existing = SERVICES.get(server);
        if (existing != null) {
            return existing;
        }
        JournalService service = new JournalService(server.overworld()::getGameTime);
        service.mute(mutedFrom());
        SERVICES.put(server, service);
        SINKS.put(server, JournalFileSink.attach(server, service));
        ThoughtBroadcast.attach(server, service); // the thinking-out-loud chat channel
        return service;
    }

    /** @see dev.luizloyola.anima.core.log.JournalService#snapshot */
    public static java.util.List<dev.luizloyola.anima.core.log.Entry> snapshot(
            MinecraftServer server, dev.luizloyola.anima.core.agent.AgentId who) {
        return of(server).snapshot(who);
    }

    /** @see dev.luizloyola.anima.core.log.JournalService#restore */
    public static void restore(MinecraftServer server, dev.luizloyola.anima.core.agent.AgentId who,
                               java.util.List<dev.luizloyola.anima.core.log.Entry> entries) {
        of(server).restore(who, entries);
    }
}
