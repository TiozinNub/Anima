package dev.luizloyola.anima.core.brain.act;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlacingTest {

    private static final Pos CELL = new Pos(1, 64, -2);

    @Test
    void onlyTheOrientationIsSetDirectly() {
        Placing placing = new Placing("minecraft:oak_door", CELL, "",
                Map.of("facing", "east", "hinge", "right", "open", "true", "powered", "false"));
        assertEquals(Map.of("facing", "east", "hinge", "right"), placing.orientation());
    }

    @Test
    void aDoubleSlabIsASlabPlacedAgainNotAnOrientation() {
        assertEquals(Map.of(), new Placing("minecraft:oak_slab", CELL, "", Map.of("type", "double")).orientation());
        assertEquals(Map.of("type", "top"),
                new Placing("minecraft:oak_slab", CELL, "", Map.of("type", "top")).orientation());
    }

    @Test
    void itReadsAsTheBlockItPlaces() {
        assertEquals("minecraft:wall_torch[facing=south] at (1, 64, -2)",
                new Placing("minecraft:torch", CELL, "minecraft:wall_torch", Map.of("facing", "south")).toString());
        assertEquals("minecraft:dirt at (1, 64, -2)", Placing.of("minecraft:dirt", CELL).toString());
    }
}
