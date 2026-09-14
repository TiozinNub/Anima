package dev.luizloyola.anima.mod.client.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BubbleBoxTest {

    @Test
    void oneLineSitsOneCellInsideTheBoxAboveTheTail() {
        BubbleBox box = BubbleBox.of(List.of(40));
        assertEquals(-10f, box.bottom(), "the tail plus the gap above the nameplate");
        assertEquals(-35f, box.top(), "one 9px line plus a cell each side");
        assertEquals(-28f, box.left());
        assertEquals(28f, box.right());
        assertEquals(-20f, box.textX(0), "centred on the head");
        assertEquals(-27f, box.textY(0));
        assertEquals(1, box.lines());
    }

    @Test
    void theTailPieceIsOneCellWideFromTheBottomEdgeToTheTip() {
        BubbleBox box = BubbleBox.of(List.of(40));
        assertEquals(-4f, box.tailLeft());
        assertEquals(4f, box.tailRight());
        assertEquals(-18f, box.tailTop(), "the bottom edge's own top");
        assertEquals(-2f, box.tailBottom(), "the tip, just above the nameplate");
    }

    @Test
    void theWidestLineSetsTheBoxAndEveryLineIsCentred() {
        BubbleBox box = BubbleBox.of(List.of(40, 100, 10));
        assertEquals(58f, box.right(), "100 plus a cell each side, halved");
        assertEquals(-55f, box.top(), "three lines on a 10px pitch, the last 9px tall, plus a cell each side");
        assertEquals(-5f, box.textX(2));
        assertEquals(-27f, box.textY(2), "the last line ends a cell above the bottom");
        assertEquals(-37f, box.textY(1));
    }

    @Test
    void aBoxIsNeverNarrowerThanTheTailPieceBetweenItsCorners() {
        BubbleBox box = BubbleBox.of(List.of(3));
        assertEquals(24f, box.right() - box.left());
    }

    @Test
    void aBubbleNeedsALine() {
        assertThrows(IllegalArgumentException.class, () -> BubbleBox.of(List.of()));
    }
}
