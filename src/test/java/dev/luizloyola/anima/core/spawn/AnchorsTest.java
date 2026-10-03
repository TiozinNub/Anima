package dev.luizloyola.anima.core.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnchorsTest {

    private static Set<String> chunks(Anchors anchors) {
        Set<String> out = new HashSet<>();
        for (int[] c : anchors.spawnChunks()) {
            out.add(c[0] + "," + c[1]);
        }
        return out;
    }

    @Test
    @DisplayName("none: no chunks, nothing near, the nearest infinitely far")
    void none() {
        Anchors none = Anchors.builder().build();
        assertTrue(none.isEmpty());
        assertTrue(none.spawnChunks().isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, none.nearestSq(0, 64, 0));
    }

    @Test
    @DisplayName("a body spawns in the 5x5 round its chunk, negative coordinates floored")
    void fiveByFive() {
        Anchors one = Anchors.builder().add(-0.5, 64, 31.9).build();
        Set<String> got = chunks(one);
        assertEquals(25, got.size());
        // x -0.5 is chunk -1, z 31.9 is chunk 1.
        assertTrue(got.contains("-3,-1"));
        assertTrue(got.contains("1,3"));
    }

    @Test
    @DisplayName("bodies sharing chunks spawn in their union, each chunk once")
    void union() {
        Anchors two = Anchors.builder().add(8, 64, 8).add(24, 64, 8).build();
        assertEquals(30, two.spawnChunks().size());
        assertEquals(30, chunks(two).size());
        Anchors.Builder b = Anchors.builder();
        for (int i = 0; i < 20; i++) {
            b.add(8 + i * 0.1, 64, 8);
        }
        assertEquals(25, b.build().spawnChunks().size());
    }

    @Test
    @DisplayName("a body is near the chunks it spawns in, and no others")
    void nearItsOwnChunks() {
        // Bodies in chunks 0, 2 and -3: chunk 2 is two out from the first and five from the last.
        Anchors anchors = Anchors.builder().add(8, 64, 8).add(40, 300, 8).add(-40, 64, 8).build();
        List<Integer> near = new ArrayList<>();
        anchors.forEachNear(2, 0, near::add);
        assertEquals(List.of(0, 1), near);
        near.clear();
        anchors.forEachNear(0, -3, near::add);
        assertEquals(List.of(), near);
    }

    @Test
    @DisplayName("a body's local cap is its 25 chunks' share of vanilla's 289")
    void localCap() {
        assertEquals(6, Anchors.localCap(70));
        assertEquals(0, Anchors.localCap(10));
        assertEquals(1, Anchors.localCap(15));
    }

    @Test
    @DisplayName("the nearest body is measured in three dimensions, as Entity.distanceToSqr")
    void nearest() {
        Anchors anchors = Anchors.builder().add(0, 0, 0).add(10, 10, 0).build();
        assertEquals(3.0, anchors.nearestSq(1, 1, 1));
        assertEquals(2.0, anchors.nearestSq(9, 10, 1));
    }
}
