package dev.luizloyola.anima.mod.client.talk;

import java.util.Collections;
import java.util.List;

/**
 * Where a bubble's box, tail and lines sit, in name-tag pixels — the pure half of drawing one,
 * so the arithmetic is tested without a font or a screen.
 *
 * <p>The space is vanilla's name-tag pose: one unit is 0.025 blocks, y grows DOWNWARD, and
 * {@code y = 0} is the top of the nameplate's own text. So everything here is negative: the tail
 * tip hangs {@link #GAP} pixels above the nameplate slot and the box stacks up from there —
 * whether or not a nameplate is showing, so a name learned mid-conversation moves nothing.
 *
 * @param widths each wrapped line's width, in the order they are drawn
 */
public record BubbleBox(float left, float top, float right, float bottom, List<Integer> widths) {

    /** What a line is wrapped to. About two and a half blocks at arm's length. */
    public static final int WRAP = 100;
    static final int LINE = 9;
    static final int PITCH = 10;
    static final int PAD_X = 3;
    static final int PAD_Y = 2;
    static final int CHAMFER = 1;
    static final int TAIL_W = 6;
    static final int TAIL_H = 4;
    static final int GAP = 2;

    public static BubbleBox of(List<Integer> widths) {
        if (widths.isEmpty()) {
            throw new IllegalArgumentException("a bubble needs a line");
        }
        float w = Collections.max(widths) + 2 * PAD_X;
        float h = widths.size() * PITCH - (PITCH - LINE) + 2 * PAD_Y;
        float bottom = -(GAP + TAIL_H);
        return new BubbleBox(-w / 2, bottom - h, w / 2, bottom, List.copyOf(widths));
    }

    public int lines() {
        return widths.size();
    }

    /** A line's left edge — every line is centred on the head. */
    public float textX(int line) {
        return -widths.get(line) / 2f;
    }

    public float textY(int line) {
        return top + PAD_Y + line * PITCH;
    }

    /** Where the tail points: the tip, on the centre line. */
    public float tailTip() {
        return bottom + TAIL_H;
    }
}
