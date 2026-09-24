package dev.luizloyola.anima.compat;

import net.minecraft.world.level.Level;

/** The time of day, which 26.1 moved off the level and onto its dimension's clock. */
public final class WorldClocks {
    private WorldClocks() {}

    /** Ticks into the current day, 0 at sunrise. */
    public static long timeOfDay(Level level) {
        //? if >=26.1 {
        return Math.floorMod(level.getDefaultClockTime(), 24_000L);
        //?} else {
        /*return Math.floorMod(level.getDayTime(), 24_000L);
        *///?}
    }
}
