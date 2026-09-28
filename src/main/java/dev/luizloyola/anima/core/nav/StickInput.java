package dev.luizloyola.anima.core.nav;

/**
 * A walking input as a player's stick holds it, relative to the way the body faces — what moves a
 * body one way while it looks another.
 *
 * @param forward along the facing, -1..1 (vanilla's {@code zza})
 * @param left    across it, to the body's left, -1..1 (vanilla's {@code xxa})
 */
public record StickInput(float forward, float left) {

    /** The input that walks toward {@code heading} while facing {@code facing}; yaws in degrees. */
    public static StickInput toward(float facing, float heading, float throttle) {
        double turn = Math.toRadians(heading - facing);
        return new StickInput((float) (Math.cos(turn) * throttle), (float) (-Math.sin(turn) * throttle));
    }
}
