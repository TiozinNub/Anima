package dev.luizloyola.anima.mod.net;

import dev.luizloyola.anima.mod.social.Talkers;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Server-side wiring for what a conversation shows a player — the bubble over a speaker's head
 * and the panel — and for what the panel sends back. Call {@link #install()} from common mod init:
 * the payload types must be registered on both sides.
 */
public final class TalkSync {
    private TalkSync() {}

    public static void install() {
        PayloadTypeRegistry.clientboundPlay().register(BubblePayload.TYPE, BubblePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TalkPayload.TYPE, TalkPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TalkActionPayload.TYPE, TalkActionPayload.CODEC);
        // Fabric runs play receivers on the server thread, so the seat is touched as the sweep does.
        ServerPlayNetworking.registerGlobalReceiver(TalkActionPayload.TYPE,
                (payload, context) -> Talkers.act(context.server(), context.player(), payload));
    }

    /** Puts {@code text} over {@code speaker}'s head for this player; a client without Anima gets nothing. */
    public static void bubble(ServerPlayer player, Entity speaker, Component text) {
        if (ServerPlayNetworking.canSend(player, BubblePayload.TYPE)) {
            ServerPlayNetworking.send(player, new BubblePayload(speaker.getId(), text));
        }
    }

    /** Replaces what this player's panel shows. */
    public static void panel(ServerPlayer player, TalkPayload state) {
        if (ServerPlayNetworking.canSend(player, TalkPayload.TYPE)) {
            ServerPlayNetworking.send(player, state);
        }
    }
}
