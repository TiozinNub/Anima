package dev.luizloyola.anima.mod.client.talk;

import dev.luizloyola.anima.mod.net.TalkPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/**
 * What the conversation panel shows and how it is painted — everything about the screen that is
 * not a screen, so the version-specific class around it is only the two render hooks and the
 * widgets.
 *
 * <p>Draws the last {@link TalkPayload} and nothing else. The one thing it decides for itself is
 * the linger: when the server closes the record the buttons go and the last line stays up for
 * {@link #LINGER_TICKS} before the panel dismisses itself, so a goodbye is read rather than
 * blinked away.
 */
@Environment(EnvType.CLIENT)
public final class TalkPanel {

    /** How the panel paints, whichever GUI API the version has. */
    public interface Canvas {
        void sprite(Identifier sprite, int x, int y, int width, int height);

        void text(FormattedCharSequence line, int x, int y, int color);

        void text(Component text, int x, int y, int color);
    }

    static final int LINGER_TICKS = 40;
    /** Latest line brightest, then the two before it. */
    private static final int[] LINE_ALPHA = {0xFF, 0xB0, 0x80};
    private static final int WHITE = 0xFFFFFF;
    private static final int HINT_COLOR = 0x80FFFFFF;
    /** The player's own name, in its own colour (decision: Luiz, 2026-09-14). */
    private static final ChatFormatting OWN_NAME = ChatFormatting.GOLD;

    private record Row(FormattedCharSequence text, int alpha) {}

    private final Runnable dismiss;
    private TalkPayload state;
    private boolean closing;
    private int linger;
    private PanelLayout layout;
    private List<Row> rows = List.of();

    public TalkPanel(TalkPayload first, Runnable dismiss) {
        this.dismiss = dismiss;
        update(first);
    }

    /** The server's next word: the whole state again, or the close. */
    public void update(TalkPayload next) {
        if (next.open()) {
            state = next;
            closing = false;
            return;
        }
        if (state == null) {
            state = next;
        } else {
            state = new TalkPayload(true, state.who(), state.lines(), List.of(), false);
        }
        closing = true;
        linger = LINGER_TICKS;
    }

    public void tick() {
        if (closing && --linger <= 0) {
            dismiss.run();
        }
    }

    public boolean closing() {
        return closing;
    }

    public boolean ready() {
        return state.ready() && !closing;
    }

    public List<TalkPayload.Offer> offers() {
        return closing ? List.of() : state.offers();
    }

    /** Button {@code index} pressed — by the mouse or its number key. */
    public void press(int index) {
        List<TalkPayload.Offer> offers = offers();
        if (ready() && index >= 0 && index < offers.size()) {
            TalkClient.say(offers.get(index).act());
        }
    }

    /** Number keys press the buttons in order. Returns whether the key was one of those. */
    public boolean key(int keyCode) {
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            press(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        return false;
    }

    /** Wraps the lines and places everything for this screen size. The screen builds its buttons off the result. */
    public PanelLayout layout(Font font, int screenWidth, int screenHeight) {
        int inner = Math.min(PanelLayout.MAX_WIDTH, screenWidth - 2 * PanelLayout.SIDE_MARGIN)
                - 2 * PanelLayout.PAD;
        List<Row> wrapped = new ArrayList<>();
        List<TalkPayload.Line> lines = state.lines();
        for (int i = 0; i < lines.size(); i++) {
            int alpha = LINE_ALPHA[Math.min(LINE_ALPHA.length - 1, lines.size() - 1 - i)];
            for (FormattedCharSequence row : font.split(composed(lines.get(i)), inner)) {
                wrapped.add(new Row(row, alpha));
            }
        }
        rows = wrapped;
        List<Integer> labels = new ArrayList<>();
        for (TalkPayload.Offer offer : offers()) {
            labels.add(font.width(offer.label()));
        }
        layout = PanelLayout.of(screenWidth, screenHeight, font.lineHeight, rows.size(), labels);
        return layout;
    }

    public void paint(Canvas canvas, Font font, Identifier frame) {
        PanelLayout l = layout;
        canvas.sprite(frame, l.x(), l.y(), l.width(), l.height());
        int left = l.x() + PanelLayout.PAD;
        canvas.text(header(), left, l.headerY(), 0xFF000000 | WHITE);
        int y = l.linesY();
        for (Row row : rows) {
            canvas.text(row.text(), left, y, (row.alpha() << 24) | WHITE);
            y += font.lineHeight + PanelLayout.LINE_GAP;
        }
        if (!closing) {
            Component hint = Component.translatable("anima.talk.esc");
            canvas.text(hint, l.x() + l.width() - PanelLayout.PAD - font.width(hint), l.footerY(),
                    HINT_COLOR);
        }
    }

    /** {@code [face]Name} — the glyph flush against the name, as in chat before it. */
    private Component header() {
        MutableComponent header = Component.empty();
        state.who().portrait().ifPresent(header::append);
        return header.append(state.who().name());
    }

    /** {@code [face]Name: line}, the player's own name in its colour and with no face. */
    private static Component composed(TalkPayload.Line line) {
        MutableComponent out = Component.empty();
        line.portrait().ifPresent(out::append);
        MutableComponent speaker = line.speaker().copy();
        if (line.mine()) {
            speaker.withStyle(OWN_NAME);
        }
        return out.append(speaker).append(": ").append(line.text());
    }
}
