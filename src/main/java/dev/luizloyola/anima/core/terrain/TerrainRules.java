package dev.luizloyola.anima.core.terrain;

/**
 * What counts as flat, open and usable ground, and what a building site may cost. The caller's
 * opinion, not the terrain's: a settlement and a pet looking for somewhere to lie down want
 * different numbers.
 *
 * <p>Defaults are the prototype's, measured on the forest world on 2026-09-25 — see
 * {@code docs/superpowers/specs/2026-09-25-topography-design.md}.
 *
 * @param smoothRadius the smoothing window's half-width; flatness is judged across it
 * @param maxSlope     the steepest smoothed rise per block still called flat
 * @param maxRough     how far one column may stand off the smoothed surface — 1 lets a
 *                     one-block hole or bump through
 * @param areaSize     the side of the square an area must fit; odd
 * @param usedMargin   how far used ground reaches past the block that marks it
 * @param footprint    the side of a building site; odd
 * @param maxTilt      the steepest plane a site may lie on
 * @param treeCost     one tree to fell, in blocks of preparation
 */
public record TerrainRules(int smoothRadius, double maxSlope, double maxRough, int areaSize,
                           int usedMargin, int footprint, double maxTilt, double treeCost) {

    public static final TerrainRules DEFAULTS =
            new TerrainRules(4, 1.0 / 8, 1.0, 9, 4, 17, 1.0 / 10, 20.0);

    public TerrainRules {
        if (smoothRadius < 1 || usedMargin < 0 || maxSlope < 0 || maxRough < 0 || maxTilt < 0
                || treeCost < 0) {
            throw new IllegalArgumentException("negative or zero rule: " + smoothRadius + ", "
                    + maxSlope + ", " + maxRough + ", " + usedMargin + ", " + maxTilt + ", " + treeCost);
        }
        // Odd, so a square has a centre column and a site a centre to report.
        if (areaSize < 1 || areaSize % 2 == 0 || footprint < 3 || footprint % 2 == 0) {
            throw new IllegalArgumentException("sizes must be odd: area " + areaSize
                    + ", footprint " + footprint);
        }
    }
}
