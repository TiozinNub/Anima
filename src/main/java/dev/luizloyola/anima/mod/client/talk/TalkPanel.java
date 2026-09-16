package dev.luizloyola.anima.mod.client.talk;

import com.mojang.blaze3d.platform.InputConstants;
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
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * What the conversation panel shows and how it is painted — everything about the screen that is
 * not a screen, so the version-specific class around it is only the render hooks and the
 * widgets.
 *
 * <p>Draws the last {@link TalkPayload} and nothing else. The one thing it decides for itself is
 * the linger: when the server closes the record the buttons go and the last line stays up for
 * {@link #LINGER_TICKS} before the panel dismisses itself, so a goodbye is read rather than
 * blinked away.
 *
 * <p><b>The counterpart's name is in the header, so their lines do not repeat it</b> (decision:
 * Luiz, 2026-09-14); the player's own lines carry the player's name in its own colour, which is
 * also what tells the two apart. The counterpart stands on the right as a paper doll.
 */
@Environment(EnvType.CLIENT)
public final class TalkPanel {

    /** How the panel paints, whichever GUI API the version has. */
    public interface Canvas {
        /** The whole background image, which is exactly the panel's size. */
        void background(Identifier texture, int x, int y, int width, int height);

        void text(FormattedCharSequence line, int x, int y, int color);

        void text(Component text, int x, int y, int color);

        /** The counterpart in the inset, following the mouse, as the inventory draws the player. */
        void doll(LivingEntity who, int left, int top, int right, int bottom, int scale,
                int mouseX, int mouseY);
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
            state = new TalkPayload(true, state.who(), state.lines(), List.of());
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

    /** Whether there is anything to press — offers are live the moment they are offered. */
    public boolean ready() {
        return !closing && !state.offers().isEmpty();
    }

    public List<TalkPayload.Offer> offers() {
        return closing ? List.of() : state.offers();
    }

    /** The counterpart's entity id, for the doll; -1 when their body is not loaded. */
    public int counterpartId() {
        return state.who().entityId();
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
        // The game's own constants, not GLFW's: 26.3 moved to SDL, whose codes differ, and
        // InputConstants follows whichever the node's KeyEvent carries.
        if (keyCode >= InputConstants.KEY_1 && keyCode <= InputConstants.KEY_9) {
            press(keyCode - InputConstants.KEY_1);
            return true;
        }
        return false;
    }

    /**
     * Wraps the lines and places everything for this screen size. The lines area holds
     * {@link PanelLayout#LINE_ROWS} rows; older rows fall off the top. The screen builds its
     * buttons off the result.
     */
    public PanelLayout layout(Font font, int screenWidth, int screenHeight) {
        List<Row> wrapped = new ArrayList<>();
        List<TalkPayload.Line> lines = state.lines();
        for (int i = 0; i < lines.size(); i++) {
            int alpha = LINE_ALPHA[Math.min(LINE_ALPHA.length - 1, lines.size() - 1 - i)];
            for (FormattedCharSequence row : font.split(composed(lines.get(i)), PanelLayout.TEXT_W)) {
                wrapped.add(new Row(row, alpha));
            }
        }
        int keep = Math.min(wrapped.size(), PanelLayout.LINE_ROWS);
        rows = List.copyOf(wrapped.subList(wrapped.size() - keep, wrapped.size()));
        layout = PanelLayout.of(screenWidth, screenHeight, offers().size());
        return layout;
    }

    public void paint(Canvas canvas, Font font, Identifier background, @Nullable LivingEntity doll,
            int mouseX, int mouseY) {
        PanelLayout l = layout;
        canvas.background(background, l.x(), l.y(), PanelLayout.WIDTH, PanelLayout.HEIGHT);
        canvas.text(header(), l.textLeft(), l.headerY(), 0xFF000000 | WHITE);
        int y = l.linesY();
        for (Row row : rows) {
            canvas.text(row.text(), l.textLeft(), y, (row.alpha() << 24) | WHITE);
            y += PanelLayout.ROW_H;
        }
        if (!closing) {
            Component hint = Component.translatable("anima.talk.esc");
            canvas.text(hint, l.textRight() - font.width(hint), l.footerY(), HINT_COLOR);
        }
        if (doll != null) {
            canvas.doll(doll, l.dollLeft(), l.dollTop(), l.dollRight(), l.dollBottom(),
                    PanelLayout.DOLL_SCALE, mouseX, mouseY);
        }
    }

    /** {@code [face]Name} — the glyph flush against the name, as in chat before it. */
    private Component header() {
        MutableComponent header = Component.empty();
        state.who().portrait().ifPresent(header::append);
        return header.append(state.who().name());
    }

    /** The counterpart's line bare; the player's own as {@code Name: line} with the name in its colour. */
    private static Component composed(TalkPayload.Line line) {
        if (!line.mine()) {
            return line.text();
        }
        return Component.empty()
                .append(line.speaker().copy().withStyle(OWN_NAME))
                .append(": ")
                .append(line.text());
    }
}
