package dev.luizloyola.anima.mod.client.talk;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the conversation panel and everything on it sits, in GUI pixels — the pure half of the
 * screen, so the arithmetic is tested without a font or a window.
 *
 * <p><b>Fixed size, one background image</b> (decision: Luiz, 2026-09-14): the panel is always
 * {@link #WIDTH}×{@link #HEIGHT}, drawn from a texture of exactly that size, centred along the
 * bottom with its lower edge {@link #BOTTOM_MARGIN} up so the hotbar and the action bar stay in
 * view under it — the action bar is where refusals and notices go while it is open. Inside, left
 * to right: a text column (header, the record's last rows, a two-column button grid, the footer
 * hint) and, on the right, the inset the counterpart stands in as a paper doll. Every position
 * here is absolute; the relative constants are the contract with the image.
 */
public record PanelLayout(int x, int y, List<Button> buttons) {

    /** One button's place: absolute x and y, its width; every button is {@link #BUTTON_H} tall. */
    public record Button(int x, int y, int width) {}

    public static final int WIDTH = 320;
    public static final int HEIGHT = 128;
    public static final int BOTTOM_MARGIN = 64;
    public static final int PAD = 6;

    /** The text column: from {@link #PAD} in, this wide. */
    public static final int TEXT_W = 244;
    public static final int HEADER_Y = 6;
    public static final int LINES_Y = 19;
    /** How many wrapped rows the lines area holds; older rows fall off the top. */
    public static final int LINE_ROWS = 4;
    public static final int ROW_H = 10;
    public static final int BUTTONS_Y = 63;
    public static final int BUTTON_W = 120;
    public static final int BUTTON_H = 20;
    public static final int BUTTON_GAP = 4;
    public static final int COLUMNS = 2;
    public static final int FOOTER_Y = 112;

    /** The doll's inset, and the scale the counterpart is drawn at in it. */
    public static final int DOLL_X = 258;
    public static final int DOLL_Y = 8;
    public static final int DOLL_W = 56;
    public static final int DOLL_H = 112;
    public static final int DOLL_SCALE = 40;

    public static PanelLayout of(int screenWidth, int screenHeight, int buttonCount) {
        int x = (screenWidth - WIDTH) / 2;
        int y = screenHeight - BOTTOM_MARGIN - HEIGHT;
        List<Button> buttons = new ArrayList<>(buttonCount);
        for (int i = 0; i < buttonCount; i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            buttons.add(new Button(x + PAD + col * (BUTTON_W + BUTTON_GAP),
                    y + BUTTONS_Y + row * (BUTTON_H + BUTTON_GAP), BUTTON_W));
        }
        return new PanelLayout(x, y, List.copyOf(buttons));
    }

    public int textLeft() {
        return x + PAD;
    }

    public int textRight() {
        return x + PAD + TEXT_W;
    }

    public int headerY() {
        return y + HEADER_Y;
    }

    public int linesY() {
        return y + LINES_Y;
    }

    public int footerY() {
        return y + FOOTER_Y;
    }

    public int dollLeft() {
        return x + DOLL_X;
    }

    public int dollTop() {
        return y + DOLL_Y;
    }

    public int dollRight() {
        return x + DOLL_X + DOLL_W;
    }

    public int dollBottom() {
        return y + DOLL_Y + DOLL_H;
    }
}
