package dev.luizloyola.anima.mod.client.appearance;

import dev.luizloyola.anima.mod.AnimaMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The two moments a baked texture stops being true with no holder left to release it —
 * {@link BakedTextures} counts holders, and a holder that goes away tidily gives its reference back.
 *
 * <ul>
 *   <li><b>A resource reload.</b> A {@code DynamicTexture} is an {@code AbstractTexture}, not a
 *       {@code ReloadableTexture}, so vanilla reloads around it: ours would keep showing pixels
 *       composited from a pack no longer loaded, over equally stale decoded source art. Both are
 *       dropped and re-baked lazily.</li>
 *   <li><b>A disconnect.</b> Removing a client entity disposes its own handle, but as the
 *       <em>only</em> path one missed removal leaks a texture until the game closes.</li>
 * </ul>
 *
 * <h2>On the deprecation warning this file produces</h2>
 * The {@code fabric-resource-loader-v1} {@code ResourceLoader} that supersedes
 * {@code SimpleSynchronousResourceReloadListener} and {@code ResourceManagerHelper} <b>renamed the
 * method between the live targets</b> ({@code registerReloader} on the 1.21.11 pins,
 * {@code registerReloadListener} on 26.1 and 26.2), so adopting it costs a Stonecutter split and a
 * new declared Fabric module. The v0 call is identical on all three and is ordinary public Fabric
 * API, which Sinytra Connector needs. When it is removed the build fails at whichever pin drops it,
 * and the split belongs in {@code compat} beside {@code GizmoFrame}.
 */
@Environment(EnvType.CLIENT)
public final class AppearanceClient implements SimpleSynchronousResourceReloadListener {

    private static final Identifier ID =
            Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "appearance_bakes");

    private AppearanceClient() {}

    public static void install() {
        // ⚠️ DISCONNECT fires on the NETWORK thread, not the render thread — confirmed from a log
        // line stamped `Netty Epoll IO #0`. Releasing a texture closes a GPU resource and a
        // NativeImage, which is render-thread work, so the sweep is handed over rather than run
        // where the event lands.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(BakedTextures::clear));
        // addReloadListener, not either registerReloadListener overload: both of those are deprecated
        // in every Fabric API version the live targets pin, and this one is present in all of them.
        // The listener names itself through getFabricId, so there is nothing to pass but the listener.
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).addReloadListener(new AppearanceClient());
    }

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    /**
     * Runs in the reload's apply stage, which is the render thread — where releasing a texture is
     * legal and where the next bake will happen anyway.
     */
    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        BakedTextures.clear();
    }
}
