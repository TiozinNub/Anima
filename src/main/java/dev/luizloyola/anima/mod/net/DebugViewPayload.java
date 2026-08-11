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
        Route route,
        List<String> brain,
        List<Belief> beliefs,
        List<PeerMark> peers,
        Sight sight,
        List<NeedMark> needs) implements CustomPacketPayload {

    public static final Type<DebugViewPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "debug_view"));

    /**
     * One leg of the walked path: the cell, how they mean to get into it, and how high inside that
     * cell the feet come to rest. The renderer colours by move type — a leap and a stroll are
     * identical as bare coordinates.
     *
     * @param surface16 feet height above the cell floor in sixteenths. It travels because a slab,
     *     stair, snow layer or dirt path puts the feet part-way up their cell, and a line pinned to
     *     the cell floor runs through the block being walked on: a staircase drew as a flat ramp.
     */
    public record Step(BlockPos pos, int move, int surface16) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Step> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Step::pos,
                        ByteBufCodecs.VAR_INT, Step::move,
                        ByteBufCodecs.VAR_INT, Step::surface16,
                        Step::new);
    }

    /**
     * Everything the PATH layer draws: the plan, where they are along it, and the two running
     * arguments the follower is having with the world.
     *
     * <p>Grouped into a sub-record because {@code StreamCodec.composite} tops out at twelve fields
     * and the nav facts alone would have eaten most of them.
     *
     * @param steps the waypoints, in order, excluding the start cell
     * @param index which waypoint is being walked toward — the boundary between the legs behind
     *     and the legs ahead
     * @param goal the requested goal cell, not the last waypoint when the route falls short (see
     *     {@link #reachedGoal})
     * @param nav {@code Navigator.describe()} — the one-line state summary
     * @param reachedGoal whether the last waypoint is the goal. Its own fact rather than prose
     *     inside {@link #nav}, because a green box on a goal the route never reaches asserts an
     *     arrival that is not planned.
     */
    public record Route(List<Step> steps, int index, Optional<BlockPos> goal, String nav,
                        boolean reachedGoal, Progress progress, Water water) {
        /** No route to draw — what an off layer and the clear snapshot both carry. */
        public static final Route NONE = new Route(
                List.of(), 0, Optional.empty(), "", false, Progress.NONE, Water.NONE);

        public static final StreamCodec<RegistryFriendlyByteBuf, Route> CODEC =
                StreamCodec.composite(
                        Step.CODEC.apply(ByteBufCodecs.list()), Route::steps,
                        ByteBufCodecs.VAR_INT, Route::index,
                        ByteBufCodecs.optional(BlockPos.STREAM_CODEC), Route::goal,
                        ByteBufCodecs.STRING_UTF8, Route::nav,
                        ByteBufCodecs.BOOL, Route::reachedGoal,
                        Progress.CODEC, Route::progress,
                        Water.CODEC, Route::water,
                        Route::new);
    }

    /**
     * How the walk is actually going: the two stall detectors, the retry budget, and the arrival
     * decision in force this tick.
     *
     * <p><b>The limits travel with the counts.</b> They are private constants of the follower, and
     * a denominator the client kept its own copy of would be a second opinion about when a body
     * gives up, drifting silently the day one of them is tuned.
     *
     * @param radius the arrival radius the last footed steering tick used; {@code 0} when that tick
     *     made no such decision (swimming, braking, not following)
     */
    public record Progress(int stuckTicks, int stuckLimit, int noMoveTicks, int noMoveLimit,
                           int repathsLeft, int maxRepaths, boolean careful, float radius) {
        /** Nothing being followed, so nothing to report. */
        public static final Progress NONE = new Progress(0, 0, 0, 0, 0, 0, false, 0.0F);

        public static final StreamCodec<RegistryFriendlyByteBuf, Progress> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Progress::stuckTicks,
                        ByteBufCodecs.VAR_INT, Progress::stuckLimit,
                        ByteBufCodecs.VAR_INT, Progress::noMoveTicks,
                        ByteBufCodecs.VAR_INT, Progress::noMoveLimit,
                        ByteBufCodecs.VAR_INT, Progress::repathsLeft,
                        ByteBufCodecs.VAR_INT, Progress::maxRepaths,
                        ByteBufCodecs.BOOL, Progress::careful,
                        ByteBufCodecs.FLOAT, Progress::radius,
                        Progress::new);
    }

    /**
     * What the route is asking of the water and what the body is doing about it — the pairing that
     * goes wrong, side by side.
     *
     * <p>While a water leg is steering, the ROUTE holds the feet at {@link #targetY} and buoyancy
     * is overruled. Drawing that height is the only way to see a hold that is sinking: a re-path
     * re-anchoring one cell lower each time reads as a body drifting to the bottom of a pool for
     * no reason.
     *
     * <p>Both states travel as NAMES, not ordinals: nothing on the client switches on them, and a
     * name keeps this record from being versioned in lockstep with two enums across the layer
     * boundary, and keeps {@code Navigator} and {@code Swimmer} out of an
     * {@code @Environment(CLIENT)} renderer's imports.
     *
     * @param intent {@code Navigator.WaterIntent} — blank when the last tick was not a water leg,
     *     which is the renderer's gate for drawing any of this
     * @param swimmer {@code Swimmer.describe()}, including its note for when the swim latch and
     *     the derived state disagree
     */
    public record Water(String intent, float targetY, String swimmer) {
        /** Dry, or no body to ask. */
        public static final Water NONE = new Water("", 0.0F, "");

        public static final StreamCodec<RegistryFriendlyByteBuf, Water> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Water::intent,
                        ByteBufCodecs.FLOAT, Water::targetY,
                        ByteBufCodecs.STRING_UTF8, Water::swimmer,
                        Water::new);

        /** Whether the last tick was steered as a water leg — the gate on drawing the hold. */
        public boolean active() {
            return !this.intent.isBlank();
        }
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
     * <p>{@link #label} carries the remembered RANGE, not the current distance: the sighting was
     * taken from wherever they stood at the time, which is the measure of how much salt to take it
     * with.
     *
     * @param visible whether a clear line reaches it from where they are standing NOW — usually
     *     false, because a glimpse outlives the look that produced it. Tested server-side because
     *     at horizon range the client may not have the chunks, and a line that vanished with
     *     somebody's render distance would read as an occlusion that isn't there.
     */
    public record Glimpse(String label, BlockPos at, boolean visible) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Glimpse> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Glimpse::label,
                        BlockPos.STREAM_CODEC, Glimpse::at,
                        ByteBufCodecs.BOOL, Glimpse::visible,
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

    /**
     * One gauge on the body's needs roster: what it measures, what it says about itself, and how
     * badly it is asking.
     *
     * <p>{@link #label} is the gauge's own {@code describe()}, composed server-side for the reason
     * {@link Belief#label} is: a gauge reads its species' aspects to know where its levels sit,
     * and the client has no profile. It already names itself ("food 14/20 sat 0.0 (peckish)"), so
     * {@link #need} is the gauge's stable KEY rather than a prefix — what a summary line can say
     * without picking a name out of prose.
     *
     * <p><b>The severity tier is not sent.</b> It is derived from the pressure
     * ({@code Severity.of}), and a second copy on the wire would be two rankings that drift. The
     * client colours the dot and draws the meter from the number it was handed.
     */
    public record NeedMark(String need, String label, float pressure) {
        public static final StreamCodec<RegistryFriendlyByteBuf, NeedMark> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, NeedMark::need,
                        ByteBufCodecs.STRING_UTF8, NeedMark::label,
                        ByteBufCodecs.FLOAT, NeedMark::pressure,
                        NeedMark::new);
    }

    /**
     * {@code StreamCodec.composite} tops out at TWELVE fields on every target this builds for.
     * Folding the four path fields into {@link Route} took this from eleven back to eight, and the
     * rule that bought the room stands: a layer's facts travel grouped into a sub-record the way
     * {@link Sight} groups the eyes, not as loose fields here.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, DebugViewPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DebugViewPayload::entityId,
                    ByteBufCodecs.VAR_INT, DebugViewPayload::layers,
                    Route.CODEC, DebugViewPayload::route,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DebugViewPayload::brain,
                    Belief.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::beliefs,
                    PeerMark.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::peers,
                    Sight.CODEC, DebugViewPayload::sight,
                    NeedMark.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::needs,
                    DebugViewPayload::new);

    /** The "draw nothing" snapshot — every layer off, no entity to anchor to. */
    public static DebugViewPayload clear() {
        return new DebugViewPayload(-1, 0, Route.NONE, List.of(),
                List.of(), List.of(), Sight.NONE, List.of());
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
