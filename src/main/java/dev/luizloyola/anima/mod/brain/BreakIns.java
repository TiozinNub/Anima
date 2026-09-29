package dev.luizloyola.anima.mod.brain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Mob;

/**
 * Who is battering a door down, and when last (shelter spec, decision 9). Vanilla plays the blows
 * only to players' clients, so {@code BreakDoorGoalMixin} tells this instead, every tick the goal
 * runs, and puts the noise on the voice channel at about the rate vanilla plays it.
 *
 * <p>Server thread only. Bounded: a mob that stopped battering long ago is dropped when the record
 * grows.
 */
public final class BreakIns {

    /** About how often vanilla plays the blow (1 tick in 20), and how often it is heard here. */
    private static final int BLOW_TICKS = 20;
    /** Past this many battering mobs, the stale ones are dropped. */
    private static final int KEPT = 256;
    /** What counts as stale when pruning: a minute of game time. */
    private static final int STALE_TICKS = 1200;

    private record Knock(long at, long heardAt) {
    }

    private static final Map<UUID, Knock> KNOCKS = new HashMap<>();

    private BreakIns() {
    }

    /** {@code mob} is at a door, breaking it: a tick of the goal. */
    public static void battering(Mob mob) {
        long now = mob.level().getGameTime();
        Knock last = KNOCKS.get(mob.getUUID());
        boolean blow = last == null || now - last.heardAt() >= BLOW_TICKS;
        KNOCKS.put(mob.getUUID(), new Knock(now, blow ? now : last.heardAt()));
        if (blow) {
            BeingVoices.voiced(mob);
        }
        if (KNOCKS.size() > KEPT) {
            KNOCKS.values().removeIf(knock -> now - knock.at() > STALE_TICKS);
        }
    }

    /** Whether {@code who} has battered a door within {@code within} ticks of {@code now}. */
    public static boolean lately(UUID who, long now, int within) {
        Knock last = KNOCKS.get(who);
        return last != null && now - last.at() <= within;
    }
}
