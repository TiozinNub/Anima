package dev.luizloyola.anima.mod.net;

import dev.luizloyola.anima.mod.AnimaMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: one spoken line, to be drawn over one body's head for a moment.
 *
 * <p>An entity id rather than an agent id: the client keeps no index from agent to entity, and a
 * player author has no agent body at all. A composed {@link Component} rather than a lang key: the
 * variant, the topic and the name an introduction says out loud are all decided server-side, and
 * the client only draws what it is handed.
 */
public record BubblePayload(int entityId, Component text) implements CustomPacketPayload {
    public static final Type<BubblePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "bubble"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BubblePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, BubblePayload::entityId,
                    ComponentSerialization.STREAM_CODEC, BubblePayload::text,
                    BubblePayload::new);

    @Override
    public Type<BubblePayload> type() {
        return TYPE;
    }
}
