package dev.luizloyola.anima.mod.client.talk;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.luizloyola.anima.compat.client.talk.BubbleFrame;
import dev.luizloyola.anima.mod.net.BubblePayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The bubbles currently over heads — client half of what a spoken line looks like since speech
 * left chat (decision: Luiz, 2026-09-13). One per entity: a body's next line replaces its last.
 *
 * <p>Delivered by {@link BubblePayload} to whoever was within the speaker's chat radius, so a
 * bubble is HEARING range, not sight range; the server decided the audience and this only draws.
 * Hooked once per frame through {@link BubbleFrame} — the only version-specific part — at the
 * camera origin, where the pose is the plain world minus the camera.
 *
 * <p>Both the receiver and the frame run on the client thread, so a plain map serves. Cleared on
 * disconnect so a line from one world never floats into the next.
 */
@Environment(EnvType.CLIENT)
public final class Bubbles {
    private Bubbles() {}

    /** How long a line stays up, and how much of the end of that is the fade. */
    static final int LIFE_TICKS = 100;
    static final int FADE_TICKS = 10;
    /** Vanilla's own name-tag range. */
    private static final double RANGE_SQ = 64 * 64;
    /**
     * Where a frame's bubble orders start. Above the handful vanilla layers with (a collar over a
     * wolf, a text display's letters over its own background), so a bubble is drawn over the lot.
     */
    private static final int ORDER = 16;

    private static final Map<Integer, Bubble> OVER = new HashMap<>();

    /** One line over one head; the wrap is cut once, with the font, the first time it is drawn. */
    private static final class Bubble {
        final Component text;
        final long shownAt;
        @Nullable List<FormattedCharSequence> lines;
        @Nullable BubbleBox box;

        Bubble(Component text, long shownAt) {
            this.text = text;
            this.shownAt = shownAt;
        }

        List<FormattedCharSequence> lines(Font font) {
            if (lines == null) {
                lines = font.split(text, BubbleBox.WRAP);
                List<Integer> widths = new ArrayList<>(lines.size());
                for (FormattedCharSequence line : lines) {
                    widths.add(font.width(line));
                }
                box = BubbleBox.of(widths);
            }
            return lines;
        }
    }

    public static void install() {
        ClientPlayNetworking.registerGlobalReceiver(BubblePayload.TYPE,
                (payload, context) -> show(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> OVER.clear());
        BubbleFrame.onFrame(Bubbles::draw);
    }

    private static void show(BubblePayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            OVER.put(payload.entityId(), new Bubble(payload.text(), level.getGameTime()));
        }
    }

    private static void draw(PoseStack pose, SubmitNodeCollector collector, Camera camera) {
        if (OVER.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            OVER.clear();
            return;
        }
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        long now = level.getGameTime();
        List<Shown> shown = new ArrayList<>(OVER.size());
        for (Iterator<Map.Entry<Integer, Bubble>> it = OVER.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Bubble> entry = it.next();
            Bubble bubble = entry.getValue();
            float age = (now - bubble.shownAt) + partial;
            if (age >= LIFE_TICKS) {
                it.remove();
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive() || entity.isInvisible()
                    || (firstPerson && entity == minecraft.getCameraEntity())) {
                continue;
            }
            double away = entity.getPosition(partial).distanceToSqr(camera.position());
            if (away > RANGE_SQ) {
                continue;
            }
            shown.add(new Shown(entity, bubble, Math.min(1f, (LIFE_TICKS - age) / FADE_TICKS),
                    away));
        }
        // Nothing depth-tests a bubble any more, so the order they go in is the only thing keeping
        // a near one over a far one: farthest first, two orders each — its box, then its lines.
        shown.sort(Comparator.comparingDouble(Shown::away).reversed());
        int order = ORDER;
        for (Shown one : shown) {
            List<FormattedCharSequence> lines = one.bubble.lines(minecraft.font);
            BubbleRenderer.submit(pose, collector, camera, level, one.entity, partial, lines,
                    one.bubble.box, one.alpha, order);
            order += 2;
        }
    }

    /** A bubble that survived the frame's culling, with the distance that says which is on top. */
    private record Shown(Entity entity, Bubble bubble, float alpha, double away) {}
}
