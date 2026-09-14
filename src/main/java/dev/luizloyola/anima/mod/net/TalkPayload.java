package dev.luizloyola.anima.mod.net;

import dev.luizloyola.anima.mod.AnimaMod;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: everything the conversation panel shows, whole, each time anything in it changes. The
 * client keeps no conversation state of its own — it draws the last one of these and nothing
 * else, so a dropped or reordered payload costs a frame, never a desync.
 *
 * <p>Composed per recipient: the name and every speaker prefix are what THIS player has earned.
 *
 * @param open false closes the panel; the rest is then empty
 * @param lines the last few lines of the record, oldest first
 * @param offers what may be said, live the moment it is offered
 */
public record TalkPayload(boolean open, Counterpart who, List<Line> lines, List<Offer> offers)
        implements CustomPacketPayload {
    public static final Type<TalkPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "talk"));

    /** Who the player is talking to: the body's entity id (-1 if not loaded), the earned name, the face. */
    public record Counterpart(int entityId, Component name, Optional<Component> portrait) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Counterpart> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Counterpart::entityId,
                        ComponentSerialization.STREAM_CODEC, Counterpart::name,
                        ComponentSerialization.OPTIONAL_STREAM_CODEC, Counterpart::portrait,
                        Counterpart::new);
    }

    /** One line: whose, what they are called to this player, their face if any, and the line itself. */
    public record Line(boolean mine, Component speaker, Optional<Component> portrait, Component text) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, Line::mine,
                        ComponentSerialization.STREAM_CODEC, Line::speaker,
                        ComponentSerialization.OPTIONAL_STREAM_CODEC, Line::portrait,
                        ComponentSerialization.STREAM_CODEC, Line::text,
                        Line::new);
    }

    /** One button: the act it says, its label, and a sample of how it might come out. */
    public record Offer(String act, Component label, Component sample) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Offer> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Offer::act,
                        ComponentSerialization.STREAM_CODEC, Offer::label,
                        ComponentSerialization.STREAM_CODEC, Offer::sample,
                        Offer::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, TalkPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, TalkPayload::open,
                    Counterpart.CODEC, TalkPayload::who,
                    Line.CODEC.apply(ByteBufCodecs.list()), TalkPayload::lines,
                    Offer.CODEC.apply(ByteBufCodecs.list()), TalkPayload::offers,
                    TalkPayload::new);

    public static TalkPayload closed() {
        return new TalkPayload(false, new Counterpart(-1, Component.empty(), Optional.empty()),
                List.of(), List.of());
    }

    @Override
    public Type<TalkPayload> type() {
        return TYPE;
    }
}
