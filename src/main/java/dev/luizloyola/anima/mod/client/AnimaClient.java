package dev.luizloyola.anima.mod.client;

import dev.luizloyola.anima.mod.client.appearance.AppearanceClient;
import net.fabricmc.api.ClientModInitializer;

/**
 * Anima's client entrypoint — the receiving half of everything the library pushes to a player.
 *
 * <p>Inspecting a mind is client work and depends on no particular creature: the contact cache
 * decides which names a player may see, the selection glow follows a pin, and the debug view draws
 * a path, a task tree, remembered places and perceived beings over whoever is selected. Baking an
 * appearance is the same kind of work — Anima turns a recipe into a texture id and owns that
 * texture's life; what the recipe <em>means</em> stays with whoever composed it.
 *
 * <p>A consumer still installs what it means to LOOK like something: an entity renderer, a screen,
 * the outline on its own body.
 */
public final class AnimaClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AgentContactsClient.install();
        DebugGlowClient.install();
        DebugViewClient.install();
        DebugViewRenderer.install();
        CellOverlayClient.install();
        CellOverlayRenderer.install();
        AppearanceClient.install();
    }
}
