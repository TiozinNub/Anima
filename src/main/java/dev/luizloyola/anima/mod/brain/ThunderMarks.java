package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.sense.Surroundings;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Where lightning struck lately — {@code PlaceMarks}' shape, for the loudest sound in the game.
 * The thunder itself only ever plays on clients, so the strike is caught as the bolt enters the
 * world, and a body asks afterwards whether it was close enough and recent enough to have heard.
 * Server-wide and transient.
 */
public final class ThunderMarks {

    /**
     * How far off a strike is still heard plainly enough to remark on. The game plays thunder to
     * everyone for miles; a settler a dozen chunks away saying "hear that?" would be hearing a
     * rumble, and this is where it stops.
     */
    public static final double EARSHOT = 192.0;
    /** How long a strike stays remarkable at all — the vocabulary decides how fresh it wants one. */
    public static final long MEMORY_TICKS = 1_200;
    /** A storm strikes often; the latest few are all anybody asks about. */
    private static final int KEEP = 32;

    record Strike(String dimension, double x, double y, double z, long tick) {
    }

    private static final Deque<Strike> STRIKES = new ArrayDeque<>();

    private ThunderMarks() {
    }

    /** Call once from mod init. */
    public static void init() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof LightningBolt) {
                mark(dimensionOf(level), entity.getX(), entity.getY(), entity.getZ(),
                        level.getGameTime());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> clear());
    }

    /** The latest strike {@code body} could have heard, if it is still fresh. */
    public static Optional<Surroundings.Thunderclap> heardBy(LivingEntity body) {
        return heard(dimensionOf(body.level()), body.getX(), body.getY(), body.getZ(),
                body.level().getGameTime());
    }

    static synchronized void mark(String dimension, double x, double y, double z, long tick) {
        STRIKES.addFirst(new Strike(dimension, x, y, z, tick));
        while (STRIKES.size() > KEEP) {
            STRIKES.removeLast();
        }
    }

    /** The newest strike within {@link #EARSHOT} and {@link #MEMORY_TICKS}; newest first, so the first match wins. */
    static synchronized Optional<Surroundings.Thunderclap> heard(String dimension, double x, double y,
            double z, long now) {
        for (Strike strike : STRIKES) {
            long ago = now - strike.tick();
            if (ago > MEMORY_TICKS) {
                break;
            }
            if (ago < 0 || !strike.dimension().equals(dimension)) {
                continue;
            }
            double dx = strike.x() - x;
            double dy = strike.y() - y;
            double dz = strike.z() - z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= EARSHOT) {
                return Optional.of(new Surroundings.Thunderclap(ago, distance));
            }
        }
        return Optional.empty();
    }

    static synchronized void clear() {
        STRIKES.clear();
    }

    private static String dimensionOf(Level level) {
        return level.dimension().identifier().toString();
    }
}
