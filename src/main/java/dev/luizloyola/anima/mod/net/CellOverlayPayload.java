package dev.luizloyola.anima.mod.net;

import dev.luizloyola.anima.mod.AnimaMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C cell overlay: world cells painted in colours, plus floating labels — the generic "show me
 * this analysis over these blocks" channel. Sent by {@code mod.debug.CellOverlays}, drawn by
 * {@code mod.client.CellOverlayRenderer}. Shapeless where {@code DebugViewPayload}
 * is one fixed shape for one fixed view: a consumer draws in-world without Anima knowing what its
 * colours mean.
 *
 * <p>{@link #source} namespaces the overlay — each source replaces its own previous frame and
 * clears with an empty payload, so two features can paint at once without erasing each other.
 * {@link #ttlTicks} is the client-side leash: a frame not refreshed within it stops drawing, so a
 * feeder that dies (or a player who leaves its range) cannot leave a stuck overlay behind.
 */
public record CellOverlayPayload(
        String source, int ttlTicks, List<Group> groups, List<FaceGroup> faces,
        List<BoxGroup> boxes, List<Label> labels)
        implements CustomPacketPayload {

    public static final Type<CellOverlayPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "cell_overlay"));

    /**
     * Cells sharing one paint: a {@code GizmoStyle}'s trio as raw ints, so the payload never
     * references a client-only class. A zero colour means "no stroke"/"no fill" — zero alpha is
     * invisible anyway, so no flag is needed. {@link #onTop} draws through the world: paint that
     * is a claim about buried blocks (a trunk inside its canopy) hidden by them is backwards.
     */
    public record Group(int stroke, float strokeWidth, int fill, boolean onTop,
                        List<BlockPos> cells) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Group> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT, Group::stroke,
                        ByteBufCodecs.FLOAT, Group::strokeWidth,
                        ByteBufCodecs.INT, Group::fill,
                        ByteBufCodecs.BOOL, Group::onTop,
                        BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), Group::cells,
                        Group::new);
    }

    /**
     * One painted block face: the {@code side} face (a {@code Direction} 3D data value) of
     * {@code cell}, drawn as a thin slab just INSIDE the cell, flush against that face's plane.
     * Shows a SURFACE between regions where a cell box could only show the regions; the slab
     * stays on its own side, so the two halves of one boundary can wear different paints.
     */
    public record Face(BlockPos cell, int side) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Face> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Face::cell,
                        ByteBufCodecs.VAR_INT, Face::side,
                        Face::new);
    }

    /** Faces sharing one paint — the face-shaped twin of {@link Group}. */
    public record FaceGroup(int stroke, float strokeWidth, int fill, boolean onTop,
                            List<Face> faces) {
        public static final StreamCodec<RegistryFriendlyByteBuf, FaceGroup> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT, FaceGroup::stroke,
                        ByteBufCodecs.FLOAT, FaceGroup::strokeWidth,
                        ByteBufCodecs.INT, FaceGroup::fill,
                        ByteBufCodecs.BOOL, FaceGroup::onTop,
                        Face.CODEC.apply(ByteBufCodecs.list()), FaceGroup::faces,
                        FaceGroup::new);
    }

    /**
     * An arbitrary axis-aligned box, inclusive on both corners — the primitive for delimiting an
     * AREA rather than describing the blocks in it. Cells are the wrong tool for a boundary: a
     * 96-block slice edge is 380 block boxes that read as a dotted caterpillar and cost a frame's
     * whole budget. Equal min and max on an axis is a flat pane — a floor tile or a vertical
     * curtain.
     */
    public record Box(BlockPos min, BlockPos max) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Box> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Box::min,
                        BlockPos.STREAM_CODEC, Box::max,
                        Box::new);
    }

    /** Boxes sharing one paint — the region-shaped twin of {@link Group}. */
    public record BoxGroup(int stroke, float strokeWidth, int fill, boolean onTop,
                           List<Box> boxes) {
        public static final StreamCodec<RegistryFriendlyByteBuf, BoxGroup> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT, BoxGroup::stroke,
                        ByteBufCodecs.FLOAT, BoxGroup::strokeWidth,
                        ByteBufCodecs.INT, BoxGroup::fill,
                        ByteBufCodecs.BOOL, BoxGroup::onTop,
                        Box.CODEC.apply(ByteBufCodecs.list()), BoxGroup::boxes,
                        BoxGroup::new);
    }

    /** One floating line of text, drawn at the cell's centre and visible through the world. */
    public record Label(String text, int color, BlockPos at) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Label> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Label::text,
                        ByteBufCodecs.INT, Label::color,
                        BlockPos.STREAM_CODEC, Label::at,
                        Label::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, CellOverlayPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, CellOverlayPayload::source,
                    ByteBufCodecs.VAR_INT, CellOverlayPayload::ttlTicks,
                    Group.CODEC.apply(ByteBufCodecs.list()), CellOverlayPayload::groups,
                    FaceGroup.CODEC.apply(ByteBufCodecs.list()), CellOverlayPayload::faces,
                    BoxGroup.CODEC.apply(ByteBufCodecs.list()), CellOverlayPayload::boxes,
                    Label.CODEC.apply(ByteBufCodecs.list()), CellOverlayPayload::labels,
                    CellOverlayPayload::new);

    /** The "stop drawing this source" frame. */
    public static CellOverlayPayload clear(String source) {
        return new CellOverlayPayload(source, 0, List.of(), List.of(), List.of(), List.of());
    }

    /** Whether this frame carries anything to draw — an empty one is a clear. */
    public boolean isEmpty() {
        return groups.isEmpty() && faces.isEmpty() && boxes.isEmpty() && labels.isEmpty();
    }

    @Override
    public Type<CellOverlayPayload> type() {
        return TYPE;
    }
}
