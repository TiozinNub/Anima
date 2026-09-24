package dev.luizloyola.anima.core.brain.sense;

/**
 * The world around the body as it can tell — the sky, the hour, the light it stands in. What a
 * body says about the day comes from here, never from the server's own view of the world.
 *
 * @param light what the body's eyes get, 0–15: sky and block light, the sky dimmed by the hour
 * @param outdoors whether any sky light reaches the body's eyes — open sky, or under a canopy,
 *     where rain and dusk still show; never a cave or a sealed room, where neither can be told
 */
public record Surroundings(Weather weather, DayPhase phase, int light, boolean outdoors) {

    public enum Weather { CLEAR, RAIN, THUNDER, SNOW }

    /** The hour, coarsely: what a body can tell by the sky. */
    public enum DayPhase {
        DAWN, DAY, DUSK, NIGHT;

        /**
         * The phase at {@code timeOfDay} as the game counts a day, 0 at sunrise: dawn is the hour
         * either side of it, dusk the hour and a half from sunset at 12,000.
         */
        public static DayPhase of(long timeOfDay) {
            long t = Math.floorMod(timeOfDay, 24_000L);
            if (t < 1_000 || t >= 23_000) {
                return DAWN;
            }
            if (t < 12_000) {
                return DAY;
            }
            return t < 13_500 ? DUSK : NIGHT;
        }
    }
}
