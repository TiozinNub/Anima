package dev.luizloyola.anima.mod.net;

import dev.luizloyola.anima.mod.AnimaMod;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C debug snapshot: one frame of everything the in-world debug view draws over the watching
 * player's selected Person. Sent on a slow cadence by {@code mod.debug.DebugView}, drawn every
 * frame by {@code mod.client.DebugViewRenderer}.
 *
 * <p>A snapshot rather than a query because every fact here lives on the server with no
 * client-side counterpart. What the client can already see is absent: position,
 * facing and eye height come off the local entity ({@link #entityId}), so the lines track it
 * smoothly instead of stuttering at the send cadence.
 *
 * <p>{@link #layers} is the authoritative {@code DebugLayer} bit mask; an off layer's collections
 * arrive empty, so the renderer checks the mask rather than guessing what empty means. A payload
 * with no layers is the explicit clear — without it the client would redraw the final frame
 * forever.
 *
 * <p>Debug traffic: never batched, never persisted, only sent to a player who asked by name.
 */
public record DebugViewPayload(
        int entityId,
        int layers,
        List<Step> path,
        int pathIndex,
        Optional<BlockPos> goal,
        String nav,
        List<String> brain,
        List<Belief> beliefs,
        List<PeerMark> peers,
        Sight sight) implements CustomPacketPayload {

    public static final Type<DebugViewPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "debug_view"));

    /**
     * One leg of the walked path: the cell, and how they mean to get into it. The renderer colours
     * by move type.
     */
    public record Step(BlockPos pos, int move) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Step> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Step::pos,
                        ByteBufCodecs.VAR_INT, Step::move,
                        Step::new);
    }

    /**
     * One remembered point of interest: what they think it is, where they'd walk to, the box
     * they believe it fills, and whether the belief has gone stale. Stale is computed server-side
     * against the last sighting, because the client has no game time it can trust.
     */
    /** {@code kind} is the POI kind's stable KEY, not a positional index: POI kinds are an open
     *  registry now, so registration order is not a stable wire format the moment a second mod
     *  declares one. */
    /**
     * @param label what this is, in a few words — built server-side because that is where the
     *     memory actually lives; the client only ever draws the string it was handed
     */
    public record Belief(String kind, String label, BlockPos anchor, BlockPos min, BlockPos max,
                         boolean stale) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Belief> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Belief::kind,
                        ByteBufCodecs.STRING_UTF8, Belief::label,
                        BlockPos.STREAM_CODEC, Belief::anchor,
                        BlockPos.STREAM_CODEC, Belief::min,
                        BlockPos.STREAM_CODEC, Belief::max,
                        ByteBufCodecs.BOOL, Belief::stale,
                        Belief::new);
    }

    /**
     * One perceived someone, as THEY have them: the name they would use, the reading in words, the
     * believed cell, and (only when that belief is live) the body to interpolate it from.
     *
     * <p>{@link #name} is {@code Peer.knownAs()}, not the account name: sound does not identify,
     * so an unseen someone reads as "someone". {@link #tell} is {@code Peer.tell()} composed
     * server-side — every observable axis (arm, legs, sneak, gaze) in one phrase, so there is one
     * description of a peer in the codebase and no copy here to drift when the sense grows an
     * axis.
     *
     * <p>{@link #pos} is always what they BELIEVE: a REMEMBERED peer's position is frozen at the
     * last live reading and a HEARD one is where the noise came from, so drawing the live entity
     * would erase the discrepancy this layer exists to show. {@link #entityId} is therefore
     * {@link #NO_BODY} for every awareness but SEEN; the gate is server-side so a client cannot
     * follow a ghost by mistake.
     */
    public record PeerMark(String name, String tell, BlockPos pos, int entityId,
                           int awareness, float distance) {
        /** No body to interpolate from — draw the believed cell as sent. */
        public static final int NO_BODY = -1;

        public static final StreamCodec<RegistryFriendlyByteBuf, PeerMark> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, PeerMark::name,
                        ByteBufCodecs.STRING_UTF8, PeerMark::tell,
                        BlockPos.STREAM_CODEC, PeerMark::pos,
                        ByteBufCodecs.VAR_INT, PeerMark::entityId,
                        ByteBufCodecs.VAR_INT, PeerMark::awareness,
                        ByteBufCodecs.FLOAT, PeerMark::distance,
                        PeerMark::new);
    }

    /**
     * One swept bearing of the far sense: which bearing it was, and the cell that TOPPED it.
     *
     * <p>{@link #bin} travels because the sweep takes every second bearing and skips any still
     * fresh, so the next entry is not the next bearing round, and the client needs the angle to
     * tell joinable neighbours from two sides of a hole.
     *
     * <p>The elevation is not sent, though the buffer holds it: {@code (top − eye) / distance},
     * and the client has both ends live, so deriving it there recolours the skyline as the body
     * climbs or crouches instead of freezing at the snapshot's eye height.
     *
     * @param truncated the bearing ended at unloaded world rather than at its range — "I could see
     *     no further", a different claim from "there was nothing there"
     */
    public record Bearing(int bin, BlockPos top, boolean truncated) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Bearing> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Bearing::bin,
                        BlockPos.STREAM_CODEC, Bearing::top,
                        ByteBufCodecs.BOOL, Bearing::truncated,
                        Bearing::new);
    }

    /**
     * One thing made out on the skyline and never examined — the gist tier, drawn beside the
     * skyline that produced it.
     *
     * <p>{@link #label} carries the remembered RANGE, not the current distance: how far off the
     * sighting was is the measure of how much salt to take it with.
     */
    public record Glimpse(String label, BlockPos at) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Glimpse> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Glimpse::label,
                        BlockPos.STREAM_CODEC, Glimpse::at,
                        Glimpse::new);
    }

    /**
     * Everything about their eyes, in one section: the two ranges, the cone both tiers share, and
     * (when the horizon layer is on) the far sweep's readout.
     *
     * <p>The dimensions come off the BODY's own profile rather than out of Anima's config, because
     * a debug view that draws somebody else's eyesight is wrong; they travel even when the horizon
     * layer is off, since the peers layer draws the near cone out of the same numbers.
     *
     * @param skyline one entry per swept, non-empty bearing, ascending by bin — whatever is drawn
     *     is what stopped the bearing, so occlusion becomes a block you can walk over and look at
     */
    public record Sight(int coneDegrees, int senseRadius, int horizonRadius,
                        List<Bearing> skyline, List<Glimpse> glimpses) {
        /** No eyes to draw — what the clear snapshot carries. */
        public static final Sight NONE = new Sight(0, 0, 0, List.of(), List.of());

        public static final StreamCodec<RegistryFriendlyByteBuf, Sight> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Sight::coneDegrees,
                        ByteBufCodecs.VAR_INT, Sight::senseRadius,
                        ByteBufCodecs.VAR_INT, Sight::horizonRadius,
                        Bearing.CODEC.apply(ByteBufCodecs.list()), Sight::skyline,
                        Glimpse.CODEC.apply(ByteBufCodecs.list()), Sight::glimpses,
                        Sight::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugViewPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DebugViewPayload::entityId,
                    ByteBufCodecs.VAR_INT, DebugViewPayload::layers,
                    Step.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::path,
                    ByteBufCodecs.VAR_INT, DebugViewPayload::pathIndex,
                    ByteBufCodecs.optional(BlockPos.STREAM_CODEC), DebugViewPayload::goal,
                    ByteBufCodecs.STRING_UTF8, DebugViewPayload::nav,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DebugViewPayload::brain,
                    Belief.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::beliefs,
                    PeerMark.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::peers,
                    Sight.CODEC, DebugViewPayload::sight,
                    DebugViewPayload::new);

    /** The "draw nothing" snapshot — every layer off, no entity to anchor to. */
    public static DebugViewPayload clear() {
        return new DebugViewPayload(-1, 0, List.of(), 0, Optional.empty(), "", List.of(),
                List.of(), List.of(), Sight.NONE);
    }

    /** Whether this snapshot carries anything to draw at all. */
    public boolean isEmpty() {
        return layers == 0 || entityId < 0;
    }

    @Override
    public Type<DebugViewPayload> type() {
        return TYPE;
    }
}
