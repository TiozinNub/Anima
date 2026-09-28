package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.core.nav.HandsOff;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Where a walk may not lay or cut: the consuming mod's sites, structures, roads and fields, asked
 * when a walk that may scale or build plans its route. Anima has no notion of any of them.
 */
public final class WorkFence {

    /** The boxes of columns near a route that it must leave as they are. */
    @FunctionalInterface
    public interface Rule {
        HandsOff around(ServerLevel level, BlockPos start, BlockPos goal);
    }

    private static volatile Rule rule = (level, start, goal) -> HandsOff.NONE;

    private WorkFence() {
    }

    /** Sets who answers {@link Rule}. The last registration wins. */
    public static void rule(Rule answer) {
        rule = answer;
    }

    static HandsOff around(ServerLevel level, BlockPos start, BlockPos goal) {
        return rule.around(level, start, goal);
    }
}
