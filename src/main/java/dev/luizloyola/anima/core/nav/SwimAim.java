package dev.luizloyola.anima.core.nav;

/**
 * Which way a swimmer strokes so that stroke and current, added, carry it straight at its waypoint:
 * a ferry's angle, as a unit direction on the horizontal.
 *
 * <p>Vanilla pushes anything that is not a player along a current at a fixed 0.014 a tick however
 * gently the water flows, against a stroke of 0.02: seven tenths of a swimmer. Aimed straight at
 * its waypoint, a body in a cross-current slides off the line until something holds it, and pressed
 * there only the stroke's share along the obstacle is left to fight the current. A settler wading
 * the lip of a waterfall sat a minute against the log beside its next cell (forest, 2026-10-02).
 *
 * <p>The stroke cancels the drift across the line and spends the rest along it. Any current weaker
 * than the stroke leaves progress, head-on included.
 */
public record SwimAim(double x, double z) {

    /**
     * The aim toward {@code (dx, dz)} under a current pushing {@code (pushX, pushZ)} a tick, for a
     * stroke of {@code stroke} a tick. Straight at the waypoint in still water.
     */
    public static SwimAim toward(double dx, double dz, double pushX, double pushZ, double stroke) {
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0.0) {
            return new SwimAim(0.0, 0.0);
        }
        double ux = dx / length;
        double uz = dz / length;
        double along = pushX * ux + pushZ * uz;
        double acrossX = pushX - along * ux;
        double acrossZ = pushZ - along * uz;
        double across = Math.sqrt(acrossX * acrossX + acrossZ * acrossZ);
        if (across >= stroke) {
            // A drift the stroke cannot cancel: all of it against the drift loses the least.
            return new SwimAim(-acrossX / across, -acrossZ / across);
        }
        double share = Math.sqrt(1.0 - (across * across) / (stroke * stroke));
        return new SwimAim(share * ux - acrossX / stroke, share * uz - acrossZ / stroke);
    }
}
