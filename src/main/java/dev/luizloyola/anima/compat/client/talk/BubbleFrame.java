package dev.luizloyola.anima.compat.client.talk;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * The two version-specific things about drawing a bubble: which Fabric event hands over the
 * submit collector with the pose at the camera origin, and what the main camera's accessor is
 * called. {@code SubmitNodeCollector} and its {@code submitText} / {@code submitCustomGeometry}
 * are identical on every live target, so the drawing lives in {@code mod.client.talk} and only
 * the hook is here — {@code GizmoFrame}'s pattern.
 *
 * <ul>
 *   <li>26.1+ (fabric-rendering-v1 23.3.1+): {@code level.LevelRenderEvents.COLLECT_SUBMITS},
 *       the pass entities are submitted in.
 *   <li>1.21.11 (fabric-rendering-v1 16.2.10): {@code world.WorldRenderEvents.AFTER_ENTITIES},
 *       whose {@code commandQueue()} is the same collector under its intermediary name.
 *   <li>26.2 renamed {@code GameRenderer.getMainCamera()} to {@code mainCamera()}.
 * </ul>
 *
 * <p>Fully-qualified event names on purpose: an import would fail to resolve on the version
 * that lacks the class.
 */
@Environment(EnvType.CLIENT)
public final class BubbleFrame {
    private BubbleFrame() {}

    /** One frame's drawing: the pose is the world minus the camera, the collector is open. */
    @FunctionalInterface
    public interface Frame {
        void draw(PoseStack pose, SubmitNodeCollector collector, Camera camera);
    }

    /** Runs {@code frame} once per frame. */
    public static void onFrame(Frame frame) {
        //? if >=26.1 {
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS
                .register(context -> frame.draw(context.poseStack(), context.submitNodeCollector(),
                        camera()));
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES
                .register(context -> frame.draw(context.matrices(), context.commandQueue(),
                        camera()));
        *///?}
    }

    private static Camera camera() {
        //? if >=26.2 {
        /*return Minecraft.getInstance().gameRenderer.mainCamera();
        *///?} else {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
        //?}
    }
}
