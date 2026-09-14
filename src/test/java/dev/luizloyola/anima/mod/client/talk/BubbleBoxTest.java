package dev.luizloyola.anima.mod.client.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BubbleBoxTest {

    @Test
    void oneLineStacksAboveTheTailWithItsPadding() {
        BubbleBox box = BubbleBox.of(List.of(40));
        assertEquals(-6f, box.bottom(), "tail height plus the gap above the nameplate");
        assertEquals(-2f, box.tailTip());
        assertEquals(-19f, box.top(), "one 9px line plus 2px padding each side");
        assertEquals(-23f, box.left());
        assertEquals(23f, box.right());
        assertEquals(-20f, box.textX(0), "centred on the head");
        assertEquals(-17f, box.textY(0));
        assertEquals(1, box.lines());
    }

    @Test
    void theWidestLineSetsTheBoxAndEveryLineIsCentred() {
        BubbleBox box = BubbleBox.of(List.of(40, 100, 10));
        assertEquals(53f, box.right(), "100 plus 3px padding, halved");
        assertEquals(-39f, box.top(), "three lines on a 10px pitch, the last 9px tall, plus padding");
        assertEquals(-5f, box.textX(2));
        assertEquals(-17f, box.textY(2), "the last line ends 2px above the bottom");
        assertEquals(-27f, box.textY(1));
    }

    @Test
    void aBubbleNeedsALine() {
        assertThrows(IllegalArgumentException.class, () -> BubbleBox.of(List.of()));
    }
}
