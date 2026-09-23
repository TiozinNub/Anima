package dev.luizloyola.anima.mod.net;

import dev.luizloyola.anima.mod.AnimaMod;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * C2S: what the player did in the panel — said an act, or closed it. A hint, not a command: the
 * server re-checks a line against the live record exactly as it does {@code /anima-say}, and a
 * close only sets the conversation aside — it ends nothing (decision: Luiz, 2026-09-23).
 */
public record TalkActionPayload(boolean close, String act, Optional<String> topic)
        implements CustomPacketPayload {
    public static final Type<TalkActionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "talk_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TalkActionPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, TalkActionPayload::close,
                    ByteBufCodecs.STRING_UTF8, TalkActionPayload::act,
                    ByteBufCodecs.<RegistryFriendlyByteBuf, String>optional(ByteBufCodecs.STRING_UTF8),
                    TalkActionPayload::topic,
                    TalkActionPayload::new);

    public static TalkActionPayload say(String act, @Nullable String topic) {
        return new TalkActionPayload(false, act, Optional.ofNullable(topic));
    }

    public static TalkActionPayload closing() {
        return new TalkActionPayload(true, "", Optional.empty());
    }

    @Override
    public Type<TalkActionPayload> type() {
        return TYPE;
    }
}
