package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.AgentProfile;
import java.util.List;

/**
 * A need's levels, turned into an answer: how badly this body wants something done, and what to
 * call how it feels.
 *
 * <p><b>The number glides, the name steps</b> (decision: Luiz, 2026-08-06): pressure interpolates
 * along the polyline through the levels' {@code (value, pressure)} corners, while the level NAME is
 * a step lookup, so bands stay legible and nothing dithers on a threshold.
 *
 * <p><b>An end of the axis that no level anchors is as urgent as this need gets</b> — the polyline
 * needs a corner at each end. Hunger pins only its floor, giving exactly {@code 1 - food/20};
 * company pins both, giving its old comfort band.
 *
 * <p>Every number is read LIVE from the profile, so retuning a species retunes bodies already
 * walking around; the levels are re-sorted on each read because they are config.
 */
public final class Ramp {

    private final List<NeedLevel> levels;
    private final double axisMin;
    private final double axisMax;

    Ramp(List<NeedLevel> levels, double axisMin, double axisMax) {
        this.levels = levels;
        this.axisMin = axisMin;
        this.axisMax = axisMax;
    }

    /** The levels this ramp runs through, in declaration order. */
    public List<NeedLevel> levels() {
        return levels;
    }

    /**
     * What this body is called at that value: the level whose boundary it has reached, keeping the
     * better name until the worse level's own value is met. With {@code sated} at 20 and
     * {@code peckish} at 14, food 17 is sated and food 14 is peckish.
     */
    public NeedLevel levelAt(AgentProfile profile, double value) {
        NeedLevel[] sorted = sorted(profile);
        for (NeedLevel level : sorted) {
            if (value <= level.value(profile)) {
                return level;
            }
        }
        return sorted[sorted.length - 1];
    }

    /**
     * Which side of comfortable {@code value} sits on: {@code -1} below, {@code 0} inside,
     * {@code +1} above.
     *
     * <p>Pressure alone cannot answer this: a two-sided need presses at both ends, and lonely and
     * crowded are the same number with opposite errands.
     *
     * <p>Comfortable is the stretch between the outermost corners whose declared pressure is zero.
     * A need restful at one end only (hunger) answers {@code -1} below it and {@code 0} at it.
     */
    public int side(AgentProfile profile, double value) {
        double[][] corners = corners(profile);
        double low = Double.NaN;
        double high = Double.NaN;
        for (double[] corner : corners) {
            if (corner[1] > 0.0) {
                continue;
            }
            if (Double.isNaN(low)) {
                low = corner[0];
            }
            high = corner[0];
        }
        if (Double.isNaN(low)) {
            return 0; // nothing about this need is ever restful; there is no side to be on
        }
        double clamped = Math.max(axisMin, Math.min(axisMax, value));
        if (clamped < low) {
            return -1;
        }
        return clamped > high ? 1 : 0;
    }

    /** How badly this body wants something done about it at that value, {@code 0..1}. */
    public double pressureAt(AgentProfile profile, double value) {
        double[][] corners = corners(profile);
        double clamped = Math.max(axisMin, Math.min(axisMax, value));
        for (int i = 1; i < corners.length; i++) {
            if (clamped <= corners[i][0]) {
                return lerp(corners[i - 1][0], corners[i - 1][1],
                        corners[i][0], corners[i][1], clamped);
            }
        }
        return corners[corners.length - 1][1];
    }

    /**
     * The polyline, left to right: every level's own corner, plus either end of the axis when no
     * level already sits on it.
     */
    private double[][] corners(AgentProfile profile) {
        NeedLevel[] sorted = sorted(profile);
        boolean pinFloor = sorted[0].value(profile) > axisMin;
        boolean pinCeiling = sorted[sorted.length - 1].value(profile) < axisMax;
        double[][] corners =
                new double[sorted.length + (pinFloor ? 1 : 0) + (pinCeiling ? 1 : 0)][2];
        int at = 0;
        if (pinFloor) {
            corners[at++] = new double[] {axisMin, 1.0};
        }
        for (NeedLevel level : sorted) {
            corners[at++] = new double[] {level.value(profile), level.pressure(profile)};
        }
        if (pinCeiling) {
            corners[at] = new double[] {axisMax, 1.0};
        }
        return corners;
    }

    private static double lerp(double x0, double y0, double x1, double y1, double x) {
        if (x1 <= x0) {
            return y1;
        }
        double t = (x - x0) / (x1 - x0);
        return Math.max(0.0, Math.min(1.0, y0 + t * (y1 - y0)));
    }

    /** The levels by their LIVE values — config, so their order is not something to assume. */
    private NeedLevel[] sorted(AgentProfile profile) {
        NeedLevel[] sorted = levels.toArray(new NeedLevel[0]);
        // Insertion sort: four or five entries, on a per-tick path, and already in order almost
        // every time — which is the one case this is linear in.
        for (int i = 1; i < sorted.length; i++) {
            NeedLevel moving = sorted[i];
            double key = moving.value(profile);
            int j = i - 1;
            while (j >= 0 && sorted[j].value(profile) > key) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = moving;
        }
        return sorted;
    }
}
