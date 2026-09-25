package dev.luizloyola.anima.core.terrain;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.Knob;

/**
 * What counts as flat, open and usable ground, and what a building site may cost.
 *
 * <p>Read from Anima's {@code terrain.*} config ({@link #configured()}), where the defaults and
 * the measurements behind them live. Flatness is loose there on purpose: ground a little work makes
 * usable counts, since no large area is dead level. See
 * {@code docs/superpowers/specs/2026-09-25-topography-design.md}.
 *
 * @param smoothRadius the smoothing window's half-width; flatness is judged across it
 * @param maxSlope     the steepest smoothed rise per block still called flat
 * @param maxRough     how far one column may stand off the smoothed surface
 * @param steepAngle   the gentlest rise, in degrees across two blocks, called steep
 * @param cliffHeight  the smallest drop to a neighbouring column called a cliff
 * @param areaSize     the side of the square an area must fit; odd
 * @param usedMargin   how far used ground reaches past the block that marks it
 * @param footprint    the side of a building site; odd
 * @param maxTilt      the steepest plane a site may lie on
 * @param treeCost     one tree to fell, in blocks of preparation
 */
public record TerrainRules(int smoothRadius, double maxSlope, double maxRough, double steepAngle,
                           int cliffHeight, int areaSize, int usedMargin, int footprint,
                           double maxTilt, double treeCost) {

    public TerrainRules {
        if (smoothRadius < 1 || usedMargin < 0 || maxSlope < 0 || maxRough < 0 || maxTilt < 0
                || treeCost < 0) {
            throw new IllegalArgumentException("negative or zero rule: " + smoothRadius + ", "
                    + maxSlope + ", " + maxRough + ", " + usedMargin + ", " + maxTilt + ", " + treeCost);
        }
        // A drop of one is a step a body walks up.
        if (steepAngle <= 0 || steepAngle >= 90 || cliffHeight < 2) {
            throw new IllegalArgumentException("steep " + steepAngle + "°, cliff " + cliffHeight);
        }
        // Odd, so a square has a centre column and a site a centre to report.
        if (areaSize < 1 || areaSize % 2 == 0 || footprint < 3 || footprint % 2 == 0) {
            throw new IllegalArgumentException("sizes must be odd: area " + areaSize
                    + ", footprint " + footprint);
        }
    }

    /**
     * The rules in force. Read on every call, so a {@code /anima config set} is seen by the next
     * look. Sizes round up to odd rather than refusing an even one.
     */
    public static TerrainRules configured() {
        ConfigValues config = Config.get();
        return new TerrainRules(
                config.i(Knob.TERRAIN_SMOOTH_RADIUS),
                config.d(Knob.TERRAIN_MAX_SLOPE),
                config.d(Knob.TERRAIN_MAX_ROUGH),
                config.d(Knob.TERRAIN_STEEP_ANGLE),
                config.i(Knob.TERRAIN_CLIFF_HEIGHT),
                config.i(Knob.TERRAIN_AREA_SIZE) | 1,
                config.i(Knob.TERRAIN_USED_MARGIN),
                config.i(Knob.TERRAIN_SITE_SIZE) | 1,
                config.d(Knob.TERRAIN_MAX_TILT),
                config.d(Knob.TERRAIN_TREE_COST));
    }
}
