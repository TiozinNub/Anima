package dev.luizloyola.anima.mod.client.talk;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the conversation panel and everything on it sits, in GUI pixels — the pure half of the
 * screen, so the arithmetic is tested without a font or a window.
 *
 * <p>A dialogue box along the bottom (decision: Luiz, 2026-09-14): centred, no wider than
 * {@link #MAX_WIDTH}, its bottom edge {@link #BOTTOM_MARGIN} up so the hotbar and the action bar
 * stay in view under it — the action bar is where refusals and notices go while it is open. Rows
 * top to bottom: the header (face and name), the record's last lines, the buttons flowed into as
 * many rows as they need, the footer hint. Every y here is absolute.
 */
public record PanelLayout(int x, int y, int width, int height, int headerY, int linesY,
        int buttonsY, int footerY, List<Button> buttons) {

    /** One button's place: absolute x and y, its width; every button is {@link #BUTTON_H} tall. */
    public record Button(int x, int y, int width) {}

    public static final int MAX_WIDTH = 320;
    public static final int SIDE_MARGIN = 20;
    public static final int BOTTOM_MARGIN = 64;
    public static final int PAD = 6;
    public static final int GAP = 4;
    public static final int LINE_GAP = 1;
    public static final int BUTTON_H = 20;
    public static final int BUTTON_GAP = 4;
    /** What a label grows by to become a button. */
    public static final int BUTTON_PAD = 16;
    public static final int FOOTER_H = 10;

    /**
     * @param lineHeight the font's
     * @param lineRows how many wrapped rows the record's lines take
     * @param labelWidths each button's label width, in the order they are drawn
     */
    public static PanelLayout of(int screenWidth, int screenHeight, int lineHeight, int lineRows,
            List<Integer> labelWidths) {
        int width = Math.min(MAX_WIDTH, screenWidth - 2 * SIDE_MARGIN);
        int inner = width - 2 * PAD;

        int headerY = PAD;
        int linesY = headerY + lineHeight + GAP;
        int linesHeight = lineRows * (lineHeight + LINE_GAP);
        int buttonsY = linesY + linesHeight + (lineRows > 0 ? GAP : 0);

        List<int[]> flowed = new ArrayList<>();
        int bx = 0;
        int by = 0;
        for (int labelWidth : labelWidths) {
            int w = Math.min(inner, labelWidth + BUTTON_PAD);
            if (bx > 0 && bx + w > inner) {
                bx = 0;
                by += BUTTON_H + BUTTON_GAP;
            }
            flowed.add(new int[] {bx, by, w});
            bx += w + BUTTON_GAP;
        }
        int buttonsHeight = flowed.isEmpty() ? 0 : by + BUTTON_H;
        int footerY = buttonsY + buttonsHeight + (flowed.isEmpty() ? 0 : GAP);
        int height = footerY + FOOTER_H + PAD;

        int x = (screenWidth - width) / 2;
        int y = screenHeight - BOTTOM_MARGIN - height;
        List<Button> buttons = new ArrayList<>(flowed.size());
        for (int[] b : flowed) {
            buttons.add(new Button(x + PAD + b[0], y + buttonsY + b[1], b[2]));
        }
        return new PanelLayout(x, y, width, height, y + headerY, y + linesY, y + buttonsY,
                y + footerY, List.copyOf(buttons));
    }

    public int innerWidth() {
        return width - 2 * PAD;
    }
}
