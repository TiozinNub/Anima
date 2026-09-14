package dev.luizloyola.anima.compat.client.talk;

import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.client.talk.PanelLayout;
import dev.luizloyola.anima.mod.client.talk.TalkClient;
import dev.luizloyola.anima.mod.client.talk.TalkPanel;
import dev.luizloyola.anima.mod.net.TalkPayload;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The conversation panel as a screen. Everything it shows is {@link TalkPanel}'s; this is the
 * frame — the widgets, the keys, and the two render hooks that differ by version (26.1's
 * retained-mode {@code GuiGraphicsExtractor}, the older immediate-mode {@code GuiGraphics}), on
 * {@code PersonInventoryScreen}'s pattern.
 *
 * <p><b>The world stays in view.</b> The background hook paints the panel and nothing else — no
 * blur, no dimming — and the game does not pause: a paused world is a settler that never answers.
 *
 * <p><b>Going away means leaving.</b> Esc, or anything else that removes this screen while the
 * record is still open, sends one <i>leave</i>; the server reads what that means. A panel the
 * server closed lingers and dismisses itself, and sends nothing.
 *
 * <p>26.2 moved the current screen off {@code Minecraft} onto its {@code gui}, so the two
 * accessors the receiver needs live here as well.
 */
@Environment(EnvType.CLIENT)
public final class TalkScreen extends Screen {
    /** The nine-slice frame; the mcmeta beside it carries the border and the tiling. */
    private static final Identifier FRAME =
            Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "talk/panel");

    private final TalkPanel panel;
    private boolean left;

    public TalkScreen(TalkPayload first) {
        super(Component.translatable("anima.talk.title"));
        this.panel = new TalkPanel(first, this::dismiss);
    }

    public void update(TalkPayload state) {
        panel.update(state);
        rebuildWidgets();
    }

    @Override
    protected void init() {
        PanelLayout layout = panel.layout(font, width, height);
        List<TalkPayload.Offer> offers = panel.offers();
        for (int i = 0; i < offers.size(); i++) {
            PanelLayout.Button at = layout.buttons().get(i);
            int index = i;
            Button button = Button.builder(offers.get(i).label(), pressed -> panel.press(index))
                    .bounds(at.x(), at.y(), at.width(), PanelLayout.BUTTON_H)
                    .tooltip(Tooltip.create(offers.get(i).sample()))
                    .build();
            button.active = panel.ready();
            addRenderableWidget(button);
        }
    }

    @Override
    public void tick() {
        panel.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return panel.key(event.key()) || super.keyPressed(event);
    }

    @Override
    public void onClose() {
        leave();
        super.onClose();
    }

    @Override
    public void removed() {
        leave();
        super.removed();
    }

    private void leave() {
        if (!left && !panel.closing()) {
            left = true;
            TalkClient.leave();
        }
    }

    private void dismiss() {
        if (minecraft != null && current(minecraft) == this) {
            open(minecraft, null);
        }
    }

    /** The screen up right now, if any. */
    public static @Nullable Screen current(Minecraft minecraft) {
        //? if >=26.2 {
        /*return minecraft.gui.screen();
        *///?} else {
        return minecraft.screen;
        //?}
    }

    /** Puts {@code screen} up, replacing whatever was; null takes the player back to the world. */
    public static void open(Minecraft minecraft, @Nullable Screen screen) {
        //? if >=26.2 {
        /*minecraft.gui.setScreen(screen);
        *///?} else {
        minecraft.setScreen(screen);
        //?}
    }

    //? if >=26.1 {
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick) {
        panel.paint(new TalkPanel.Canvas() {
            @Override
            public void sprite(Identifier sprite, int x, int y, int width, int height) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
            }

            @Override
            public void text(net.minecraft.util.FormattedCharSequence line, int x, int y, int color) {
                graphics.text(font, line, x, y, color);
            }

            @Override
            public void text(Component text, int x, int y, int color) {
                graphics.text(font, text, x, y, color);
            }
        }, font, FRAME);
    }
    //?} else {
    /*@Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        panel.paint(new TalkPanel.Canvas() {
            @Override
            public void sprite(Identifier sprite, int x, int y, int width, int height) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
            }

            @Override
            public void text(net.minecraft.util.FormattedCharSequence line, int x, int y, int color) {
                graphics.drawString(font, line, x, y, color);
            }

            @Override
            public void text(Component text, int x, int y, int color) {
                graphics.drawString(font, text, x, y, color);
            }
        }, font, FRAME);
    }
    *///?}
}
