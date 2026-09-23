package dev.luizloyola.anima.mod.client.talk;

import dev.luizloyola.anima.compat.client.talk.TalkScreen;
import dev.luizloyola.anima.mod.net.TalkActionPayload;
import dev.luizloyola.anima.mod.net.TalkPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * The conversation panel's two ends of the wire: a {@link TalkPayload} opens, refreshes or
 * closes the screen, and what the player does in it goes back as a {@link TalkActionPayload}.
 * Nothing here decides anything — the server composed the state and will re-check the act.
 */
@Environment(EnvType.CLIENT)
public final class TalkClient {
    private TalkClient() {}

    public static void install() {
        ClientPlayNetworking.registerGlobalReceiver(TalkPayload.TYPE,
                (payload, context) -> receive(context.client(), payload));
    }

    private static void receive(Minecraft minecraft, TalkPayload state) {
        if (TalkScreen.current(minecraft) instanceof TalkScreen panel) {
            panel.update(state);
        } else if (state.open()) {
            TalkScreen.open(minecraft, new TalkScreen(state));
        }
        // A close for a panel that is already gone — Esc got there first — is nothing to do.
    }

    public static void say(String act) {
        ClientPlayNetworking.send(TalkActionPayload.say(act, null));
    }

    public static void putDown() {
        ClientPlayNetworking.send(TalkActionPayload.closing());
    }
}
