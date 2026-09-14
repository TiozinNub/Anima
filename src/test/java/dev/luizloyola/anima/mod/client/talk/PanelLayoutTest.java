package dev.luizloyola.anima.mod.client.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PanelLayoutTest {

    @Test
    void sitsCentredAboveTheHotbarAndActionBar() {
        PanelLayout l = PanelLayout.of(800, 400, 0);
        assertEquals(240, l.x());
        assertEquals(400 - PanelLayout.BOTTOM_MARGIN - PanelLayout.HEIGHT, l.y());
        assertEquals(l.y() + 6, l.headerY());
        assertEquals(l.y() + 19, l.linesY());
        assertEquals(l.y() + 112, l.footerY());
        assertEquals(l.x() + 6, l.textLeft());
        assertEquals(l.x() + 250, l.textRight());
    }

    @Test
    void buttonsFillATwoColumnGrid() {
        PanelLayout l = PanelLayout.of(800, 400, 3);
        List<PanelLayout.Button> b = l.buttons();
        assertEquals(3, b.size());
        assertEquals(l.textLeft(), b.get(0).x());
        assertEquals(l.y() + 63, b.get(0).y());
        assertEquals(b.get(0).x() + 124, b.get(1).x());
        assertEquals(b.get(0).y(), b.get(1).y());
        assertEquals(b.get(0).x(), b.get(2).x(), "the third starts the second row");
        assertEquals(b.get(0).y() + 24, b.get(2).y());
        for (PanelLayout.Button button : b) {
            assertEquals(120, button.width());
            assertTrue(button.x() + button.width() <= l.textRight(), "inside the text column");
        }
    }

    @Test
    void everyRegionFitsInsideTheImage() {
        assertTrue(PanelLayout.PAD + PanelLayout.TEXT_W < PanelLayout.DOLL_X, "text column clear of the doll");
        assertEquals(PanelLayout.TEXT_W, 2 * PanelLayout.BUTTON_W + PanelLayout.BUTTON_GAP, "two buttons span the column");
        assertTrue(PanelLayout.LINES_Y + PanelLayout.LINE_ROWS * PanelLayout.ROW_H <= PanelLayout.BUTTONS_Y);
        assertTrue(PanelLayout.BUTTONS_Y + 2 * PanelLayout.BUTTON_H + PanelLayout.BUTTON_GAP <= PanelLayout.FOOTER_Y);
        assertTrue(PanelLayout.DOLL_X + PanelLayout.DOLL_W <= PanelLayout.WIDTH - PanelLayout.PAD);
        assertTrue(PanelLayout.DOLL_Y + PanelLayout.DOLL_H <= PanelLayout.HEIGHT - PanelLayout.PAD);
        PanelLayout l = PanelLayout.of(800, 400, 0);
        assertEquals(l.x() + 258, l.dollLeft());
        assertEquals(l.y() + 8, l.dollTop());
        assertEquals(l.dollLeft() + 56, l.dollRight());
        assertEquals(l.dollTop() + 112, l.dollBottom());
    }
}
