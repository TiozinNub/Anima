package dev.luizloyola.anima.mod.client.talk;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Draws one bubble: a light box with a tail, dark text inside, over a body's head.
 *
 * <p><b>The pose is vanilla's name tag, exactly</b> — read off the 26.1 bytecode and the same on
 * 1.21.11: the entity's position plus its {@code NAME_TAG} attachment plus half a block, turned
 * to face the camera, scaled by {@link EntityRenderer#NAMETAG_SCALE} with y flipped. So a bubble
 * sits where a name tag would and follows the head; {@link BubbleBox} says where in that space
 * the box and the lines go.
 *
 * <p><b>Lit between full-bright and the world</b> (decision: Luiz, 2026-09-14): block light under
 * the speaker is floored at {@link #MIN_BLOCK_LIGHT}, sky light read as is, so a bubble dims at
 * night but never disappears into a cave. The box goes through the text-background render type,
 * which is translucent and depth-tested — a wall hides it — and the text is polygon-offset over
 * it, the way a text display keeps its own letters above its own background.
 */
@Environment(EnvType.CLIENT)
final class BubbleRenderer {
    private BubbleRenderer() {}

    /** Off-white, most of the way to opaque; near-black text. Alpha is applied at draw time. */
    static final int BOX_RGB = 0xF0F0EB;
    static final int BOX_ALPHA = 230;
    static final int TEXT_RGB = 0x202020;
    /** The font treats an alpha under 4 as opaque, so a fade has to stop short of it. */
    static final int MIN_TEXT_ALPHA = 8;
    /** Of 15 — a torch two blocks off. A starting number, to be judged at night. */
    static final int MIN_BLOCK_LIGHT = 8;

    static void submit(PoseStack pose, SubmitNodeCollector collector, Font font, Camera camera,
            ClientLevel level, Entity entity, float partial, List<FormattedCharSequence> lines,
            BubbleBox box, float alpha) {
        Vec3 attachment = entity.getAttachments()
                .getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partial));
        if (attachment == null) {
            return;
        }
        Vec3 at = entity.getPosition(partial).add(attachment).subtract(camera.position());
        int light = light(level, entity, partial);
        pose.pushPose();
        pose.translate(at.x, at.y + 0.5, at.z);
        pose.mulPose(camera.rotation());
        pose.scale(EntityRenderer.NAMETAG_SCALE, -EntityRenderer.NAMETAG_SCALE,
                EntityRenderer.NAMETAG_SCALE);

        int boxColor = argb(Math.round(BOX_ALPHA * alpha), BOX_RGB);
        collector.submitCustomGeometry(pose, RenderTypes.textBackground(), (p, consumer) -> {
            float c = BubbleBox.CHAMFER;
            quad(consumer, p, box.left() + c, box.top(), box.right() - c, box.bottom(), boxColor, light);
            quad(consumer, p, box.left(), box.top() + c, box.left() + c, box.bottom() - c, boxColor, light);
            quad(consumer, p, box.right() - c, box.top() + c, box.right(), box.bottom() - c, boxColor, light);
            float half = BubbleBox.TAIL_W / 2f;
            // A triangle in a quad list: the last vertex repeats. Same winding as the box.
            vertex(consumer, p, -half, box.bottom(), boxColor, light);
            vertex(consumer, p, 0, box.tailTip(), boxColor, light);
            vertex(consumer, p, half, box.bottom(), boxColor, light);
            vertex(consumer, p, half, box.bottom(), boxColor, light);
        });

        int textColor = argb(Math.max(MIN_TEXT_ALPHA, Math.round(255 * alpha)), TEXT_RGB);
        for (int i = 0; i < lines.size(); i++) {
            collector.submitText(pose, box.textX(i), box.textY(i), lines.get(i), false,
                    Font.DisplayMode.POLYGON_OFFSET, light, textColor, 0, 0);
        }
        pose.popPose();
    }

    /** Vanilla's glyph order — left-top, left-bottom, right-bottom, right-top — which is what this pose shows face-on. */
    private static void quad(VertexConsumer consumer, PoseStack.Pose p, float x0, float y0,
            float x1, float y1, int color, int light) {
        vertex(consumer, p, x0, y0, color, light);
        vertex(consumer, p, x0, y1, color, light);
        vertex(consumer, p, x1, y1, color, light);
        vertex(consumer, p, x1, y0, color, light);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose p, float x, float y,
            int color, int light) {
        consumer.addVertex(p, x, y, 0).setColor(color).setLight(light);
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
