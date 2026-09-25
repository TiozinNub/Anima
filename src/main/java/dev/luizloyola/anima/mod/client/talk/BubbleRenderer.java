package dev.luizloyola.anima.mod.client.talk;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.luizloyola.anima.compat.client.talk.BubbleFrame;
import dev.luizloyola.anima.mod.AnimaMod;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Draws one bubble: the atlas's pieces tiled into a box with a tail, dark text inside, over a
 * body's head.
 *
 * <p><b>The pose is vanilla's name tag, exactly</b> — read off the 26.1 bytecode and the same on
 * 1.21.11: the entity's position plus its {@code NAME_TAG} attachment plus half a block, turned
 * to face the camera, scaled by {@link EntityRenderer#NAMETAG_SCALE} with y flipped. So a bubble
 * sits where a name tag would and follows the head; {@link BubbleBox} says where in that space
 * the pieces and the lines go.
 *
 * <p><b>The atlas</b> is {@value #ATLAS_W}×{@value #ATLAS_H}, {@link BubbleBox#CELL}-pixel cells:
 * columns 0–2 by rows 0–2 are the nine-slice (corners, edges, centre), and column 3 rows 0–1 is
 * the tail piece — the bottom edge's middle segment with the tail under it, drawn between the two
 * runs of bottom edge so nothing overlaps. Edges and the centre repeat at their native size; the
 * last repeat is clipped, its UVs with it. Goes through the see-through text render type with the
 * atlas as its texture, and the lines through the same one an order later — a text display layers
 * its own background the same way.
 *
 * <p><b>Nothing depth-tests a bubble</b> (2026-09-24). It did until a wall cut one in half: the
 * box is a flat billboard up to three blocks wide, so turning the camera swept its plane through
 * whatever the speaker stood against and every fragment past the surface went missing. A wall
 * still hides a bubble, but as a whole — {@link #inSight} asks once whether the camera reaches
 * the speaker, and a speaker out of sight draws nothing. Order does what depth did:
 * {@link Bubbles} hands out two per bubble, farthest speaker first.
 *
 * <p><b>Lit between full-bright and the world</b> (decision: Luiz, 2026-09-14): block light under
 * the speaker is floored at {@link #MIN_BLOCK_LIGHT}, sky light read as is, so a bubble dims at
 * night but never disappears into a cave.
 */
@Environment(EnvType.CLIENT)
final class BubbleRenderer {
    private BubbleRenderer() {}

    static final Identifier ATLAS =
            Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "textures/talk/bubble.png");
    static final int ATLAS_W = 32;
    static final int ATLAS_H = 24;
    private static final int C = BubbleBox.CELL;

    static final int TEXT_RGB = 0x202020;
    /** The font treats an alpha under 4 as opaque, so a fade has to stop short of it. */
    static final int MIN_TEXT_ALPHA = 8;
    /** Of 15 — a torch two blocks off. A starting number, to be judged at night. */
    static final int MIN_BLOCK_LIGHT = 8;

    static void submit(PoseStack pose, SubmitNodeCollector collector, Camera camera,
            ClientLevel level, Entity entity, float partial, List<FormattedCharSequence> lines,
            BubbleBox box, float alpha, int order) {
        Vec3 attachment = entity.getAttachments()
                .getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partial));
        if (attachment == null) {
            return;
        }
        if (!inSight(level, camera, entity, partial)) {
            return;
        }
        Vec3 at = entity.getPosition(partial).add(attachment).add(0, 0.5, 0)
                .subtract(camera.position());
        int light = light(level, entity, partial);
        pose.pushPose();
        pose.translate(at.x, at.y, at.z);
        BubbleFrame.faceCamera(pose, camera);
        pose.scale(EntityRenderer.NAMETAG_SCALE, -EntityRenderer.NAMETAG_SCALE,
                EntityRenderer.NAMETAG_SCALE);

        int tint = argb(Math.round(255 * alpha), 0xFFFFFF);
        OrderedSubmitNodeCollector under = collector.order(order);
        under.submitCustomGeometry(pose, RenderTypes.textSeeThrough(ATLAS), (p, consumer) -> {
            float l = box.left();
            float t = box.top();
            float r = box.right();
            float b = box.bottom();
            cell(consumer, p, l, t, l + C, t + C, 0, 0, tint, light);
            cell(consumer, p, r - C, t, r, t + C, 2, 0, tint, light);
            cell(consumer, p, l, b - C, l + C, b, 0, 2, tint, light);
            cell(consumer, p, r - C, b - C, r, b, 2, 2, tint, light);
            tile(consumer, p, l + C, t, r - C, t + C, 1, 0, tint, light);
            tile(consumer, p, l, t + C, l + C, b - C, 0, 1, tint, light);
            tile(consumer, p, r - C, t + C, r, b - C, 2, 1, tint, light);
            tile(consumer, p, l + C, b - C, box.tailLeft(), b, 1, 2, tint, light);
            tile(consumer, p, box.tailRight(), b - C, r - C, b, 1, 2, tint, light);
            tile(consumer, p, l + C, t + C, r - C, b - C, 1, 1, tint, light);
            piece(consumer, p, box.tailLeft(), box.tailTop(), box.tailRight(), box.tailBottom(),
                    3 * C, 0, C, 2 * C, tint, light);
        });

        int textColor = argb(Math.max(MIN_TEXT_ALPHA, Math.round(255 * alpha)), TEXT_RGB);
        OrderedSubmitNodeCollector over = collector.order(order + 1);
        for (int i = 0; i < lines.size(); i++) {
            over.submitText(pose, box.textX(i), box.textY(i), lines.get(i), false,
                    Font.DisplayMode.SEE_THROUGH, light, textColor, 0, 0);
        }
        pose.popPose();
    }

    /**
     * Whether the camera reaches the speaker's eyes in a straight line. The speaker, not the
     * bubble: the box hangs a block over the head, where a ceiling or an overhang can cut the
     * line while the body it belongs to is in plain view. Visual shapes, and fluids ignored — a
     * bubble underwater is still readable. Blocks only: a body in the way never hides a bubble,
     * which is the rule a nameplate follows too.
     */
    private static boolean inSight(ClientLevel level, Camera camera, Entity entity, float partial) {
        return level.clip(new ClipContext(camera.position(), entity.getEyePosition(partial),
                        ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity))
                .getType() == HitResult.Type.MISS;
    }

    /** One atlas cell, drawn whole. */
    private static void cell(VertexConsumer consumer, PoseStack.Pose p, float x0, float y0,
            float x1, float y1, int col, int row, int color, int light) {
        piece(consumer, p, x0, y0, x1, y1, col * C, row * C, C, C, color, light);
    }

    /** Cell {@code (col, row)} repeated over the rectangle, both ways, the last repeat clipped. */
    private static void tile(VertexConsumer consumer, PoseStack.Pose p, float x0, float y0,
            float x1, float y1, int col, int row, int color, int light) {
        for (float y = y0; y < y1; y += C) {
            float h = Math.min(C, y1 - y);
            for (float x = x0; x < x1; x += C) {
                float w = Math.min(C, x1 - x);
                piece(consumer, p, x, y, x + w, y + h, col * C, row * C, w, h, color, light);
            }
        }
    }

    /**
     * A rectangle of the atlas ({@code ax, ay, aw, ah} in atlas pixels) onto a rectangle of the
     * bubble, in vanilla's glyph order — left-top, left-bottom, right-bottom, right-top — which
     * is what this pose shows face-on.
     */
    private static void piece(VertexConsumer consumer, PoseStack.Pose p, float x0, float y0,
            float x1, float y1, float ax, float ay, float aw, float ah, int color, int light) {
        float u0 = ax / ATLAS_W;
        float u1 = (ax + aw) / ATLAS_W;
        float v0 = ay / ATLAS_H;
        float v1 = (ay + ah) / ATLAS_H;
        consumer.addVertex(p, x0, y0, 0).setColor(color).setUv(u0, v0).setLight(light);
        consumer.addVertex(p, x0, y1, 0).setColor(color).setUv(u0, v1).setLight(light);
        consumer.addVertex(p, x1, y1, 0).setColor(color).setUv(u1, v1).setLight(light);
        consumer.addVertex(p, x1, y0, 0).setColor(color).setUv(u1, v0).setLight(light);
    }

    /** Packed light coords at the speaker's eyes, block light floored. The packing is the lightmap's own: block in bits 4–7, sky in 20–23. */
    private static int light(ClientLevel level, Entity entity, float partial) {
        BlockPos at = BlockPos.containing(entity.getEyePosition(partial));
        int block = Math.max(MIN_BLOCK_LIGHT, level.getBrightness(LightLayer.BLOCK, at));
        int sky = level.getBrightness(LightLayer.SKY, at);
        return (block << 4) | (sky << 20);
    }

    private static int argb(int alpha, int rgb) {
        return (Mth.clamp(alpha, 0, 255) << 24) | rgb;
    }
}
