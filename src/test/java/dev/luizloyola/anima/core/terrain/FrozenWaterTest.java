package dev.luizloyola.anima.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.terrain.FrozenWater.Cell;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class FrozenWaterTest {

    /** Vanilla's: the sea's top water block is at 62. */
    private static final int SEA_LEVEL = 63;

    /** A column drawn top-down from {@code top}; anything below the drawing is stone. */
    private static IntFunction<Cell> column(int top, Cell... fromTop) {
        return y -> {
            int i = top - y;
            return i >= 0 && i < fromTop.length ? fromTop[i] : Cell.OTHER;
        };
    }

    private static int surface(int top, Cell... fromTop) {
        return FrozenWater.surface(column(top, fromTop), top, SEA_LEVEL, 48);
    }

    @Test
    void sheetIceOnALakeIsTheLake() {
        assertEquals(70, surface(70, Cell.ICE, Cell.WATER, Cell.WATER));
    }

    @Test
    void sheetIceOnTheSeaIsTheSea() {
        assertEquals(62, surface(62, Cell.ICE, Cell.WATER));
    }

    /** Vanilla caps icebergs with snow blocks, so the top is not ice at all. */
    @Test
    void aSnowCappedIcebergIsTheSea() {
        assertEquals(62, surface(75, Cell.SNOW, Cell.SNOW, Cell.ICE, Cell.ICE, Cell.ICE, Cell.ICE,
                Cell.ICE, Cell.ICE, Cell.ICE, Cell.ICE, Cell.ICE, Cell.ICE, Cell.ICE, Cell.ICE,
                Cell.ICE, Cell.WATER));
    }

    /** Ice to the sea floor, no water under it anywhere: the keel below the waterline says sea. */
    @Test
    void aGroundedIcebergIsTheSea() {
        Cell[] ice = new Cell[30];
        java.util.Arrays.fill(ice, Cell.ICE);
        assertEquals(62, surface(70, ice));
    }

    @Test
    void anIceSpikeOnASnowBlockIsLand() {
        assertEquals(FrozenWater.LAND, surface(90, Cell.ICE, Cell.ICE, Cell.ICE, Cell.ICE, Cell.SNOW));
    }

    @Test
    void aRoadOfBlueIceIsLand() {
        assertEquals(FrozenWater.LAND, surface(70, Cell.ICE, Cell.OTHER));
    }

    @Test
    void snowOnGrassIsLand() {
        assertEquals(FrozenWater.LAND, surface(70, Cell.SNOW, Cell.OTHER));
    }

    @Test
    void packedIceOnStoneAboveTheSeaIsLand() {
        assertEquals(FrozenWater.LAND, surface(66, Cell.ICE, Cell.ICE, Cell.ICE, Cell.OTHER));
    }

    /** A spike taller than the walk is not water just because the walk ran out. */
    @Test
    void aColumnTooTallToWalkIsLand() {
        Cell[] spike = new Cell[60];
        java.util.Arrays.fill(spike, Cell.ICE);
        assertEquals(FrozenWater.LAND, FrozenWater.surface(column(140, spike), 140, SEA_LEVEL, 48));
    }

    @Test
    void iceOnLavaIsNotWater() {
        assertEquals(FrozenWater.LAND, surface(40, Cell.ICE, Cell.OTHER));
    }
}
