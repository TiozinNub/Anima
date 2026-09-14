package dev.luizloyola.anima.mod.client.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PanelLayoutTest {

    private static final int LINE = 9;

    @Test
    void sitsCentredAboveTheHotbarAndActionBar() {
        PanelLayout l = PanelLayout.of(800, 400, LINE, 2, List.of(60, 60));
        assertEquals(PanelLayout.MAX_WIDTH, l.width(), "wide screens cap the width");
        assertEquals((800 - PanelLayout.MAX_WIDTH) / 2, l.x());
        assertEquals(400 - PanelLayout.BOTTOM_MARGIN, l.y() + l.height(), "the bottom edge clears the hotbar");
        assertEquals(l.y() + PanelLayout.PAD, l.headerY());
        assertEquals(l.headerY() + LINE + PanelLayout.GAP, l.linesY());
        assertEquals(l.linesY() + 2 * (LINE + PanelLayout.LINE_GAP) + PanelLayout.GAP, l.buttonsY());
        assertEquals(l.buttonsY() + PanelLayout.BUTTON_H + PanelLayout.GAP, l.footerY());
        assertEquals(l.footerY() + PanelLayout.FOOTER_H + PanelLayout.PAD, l.y() + l.height());
    }

    @Test
    void narrowScreensKeepTheSideMargins() {
        PanelLayout l = PanelLayout.of(300, 300, LINE, 0, List.of());
        assertEquals(300 - 2 * PanelLayout.SIDE_MARGIN, l.width());
        assertEquals(PanelLayout.SIDE_MARGIN, l.x());
        assertEquals(l.linesY(), l.buttonsY(), "no lines, no gap");
        assertEquals(l.buttonsY(), l.footerY(), "no buttons, no gap");
    }

    @Test
    void buttonsFlowIntoRowsAndNeverLeaveThePanel() {
        // 320 wide, 308 inner: 90+16 = 106 each, three fit (106*3 + 4*2 = 326 > 308), so two rows.
        PanelLayout l = PanelLayout.of(800, 400, LINE, 1, List.of(90, 90, 90, 90));
        List<PanelLayout.Button> b = l.buttons();
        assertEquals(4, b.size());
        assertEquals(l.x() + PanelLayout.PAD, b.get(0).x());
        assertEquals(b.get(0).x() + 106 + PanelLayout.BUTTON_GAP, b.get(1).x());
        assertEquals(b.get(0).y(), b.get(1).y());
        assertEquals(b.get(0).x(), b.get(2).x(), "the third wraps to a new row");
        assertEquals(b.get(0).y() + PanelLayout.BUTTON_H + PanelLayout.BUTTON_GAP, b.get(2).y());
        assertEquals(b.get(2).y(), b.get(3).y());
        for (PanelLayout.Button button : b) {
            assertTrue(button.x() + button.width() <= l.x() + l.width() - PanelLayout.PAD);
        }
        assertEquals(l.buttonsY() + 2 * PanelLayout.BUTTON_H + PanelLayout.BUTTON_GAP + PanelLayout.GAP,
                l.footerY(), "two rows of buttons");
    }

    @Test
    void aLabelWiderThanThePanelIsClamped() {
        PanelLayout l = PanelLayout.of(300, 300, LINE, 0, List.of(1000));
        assertEquals(l.innerWidth(), l.buttons().get(0).width());
    }
}
