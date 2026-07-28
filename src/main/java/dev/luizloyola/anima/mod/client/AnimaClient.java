package dev.luizloyola.anima.mod.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Anima's client entrypoint — the receiving half of everything the library pushes to a player.
 *
 * <p>Inspecting a mind is client work: the contact cache, the selection glow, and the debug view's
 * path, task tree, remembered places and perceived beings. None of it depends on what kind of
 * creature is watched, so none belongs to a consumer — which installs only what it means to LOOK
 * like something.
 */
public final class AnimaClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AgentContactsClient.install();
        DebugGlowClient.install();
        DebugViewClient.install();
        DebugViewRenderer.install();
    }
}
