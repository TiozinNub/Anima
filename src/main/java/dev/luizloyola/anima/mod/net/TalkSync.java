package dev.luizloyola.anima.mod.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Server-side wiring for what a conversation shows a player: the bubble over a speaker's head.
 * Call {@link #install()} from common mod init — the payload type must be registered on both
 * sides.
 */
public final class TalkSync {
    private TalkSync() {}

    public static void install() {
        PayloadTypeRegistry.clientboundPlay().register(BubblePayload.TYPE, BubblePayload.CODEC);
    }

    /** Puts {@code text} over {@code speaker}'s head for this player; a client without Anima gets nothing. */
    public static void bubble(ServerPlayer player, Entity speaker, Component text) {
        if (ServerPlayNetworking.canSend(player, BubblePayload.TYPE)) {
            ServerPlayNetworking.send(player, new BubblePayload(speaker.getId(), text));
        }
    }
}
