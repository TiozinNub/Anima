package dev.luizloyola.anima.mod.client;

import dev.luizloyola.anima.compat.client.debug.GizmoFrame;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The cell overlay's client half: immediate-mode gizmo boxes, re-emitted every frame from the
 * latest {@link CellOverlayClient} frames, through the same hook and discipline as
 * {@link DebugViewRenderer}.
 *
 * <p>Boxes are depth-tested, labels are not: a feeder usually paints solid terrain, and
 * x-raying every painted cell is unreadable where the painted <em>shell</em> reads well. A label
 * you must walk around a tree to read labels nothing.
 */
@Environment(EnvType.CLIENT)
public final class CellOverlayRenderer {
    private CellOverlayRenderer() {}

    /** Boxes grow a hair past the block so painted faces never z-fight the faces they describe. */
    private static final float CELL_OUTSET = 0.01F;

    /** Thickness of a painted face's slab — thin enough to read as a membrane, not a box. */
    private static final float FACE_THICKNESS = 0.04F;

    /**
     * Clearance between a face slab and its plane. The two sides of one boundary are separate
     * slabs meeting there, and touching exactly would make their interface coplanar and z-fight.
     */
    private static final float FACE_GAP = 0.002F;

    private static final float LABEL_SCALE = 0.42F;

    public static void install() {
        GizmoFrame.onFrame(CellOverlayRenderer::draw);
    }

    private static void draw() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || CellOverlayClient.frames().isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Map.Entry<String, CellOverlayClient.Held>> frames =
                CellOverlayClient.frames().entrySet().iterator();
        while (frames.hasNext()) {
            CellOverlayClient.Held held = frames.next().getValue();
            if (now > held.expiresAt()) {
                frames.remove(); // the feeder went quiet — the leash, not an error
                continue;
            }
            for (CellOverlayPayload.Group group : held.overlay().groups()) {
                GizmoStyle style = style(group.stroke(), group.strokeWidth(), group.fill());
                for (BlockPos cell : group.cells()) {
                    var box = Gizmos.cuboid(cell, CELL_OUTSET, style);
                    if (group.onTop()) {
                        box.setAlwaysOnTop();
                    }
                }
            }
            for (CellOverlayPayload.FaceGroup group : held.overlay().faces()) {
                GizmoStyle style = style(group.stroke(), group.strokeWidth(), group.fill());
                for (CellOverlayPayload.Face face : group.faces()) {
                    var slab = Gizmos.cuboid(faceSlab(face), style);
                    if (group.onTop()) {
                        slab.setAlwaysOnTop();
                    }
                }
            }
            for (CellOverlayPayload.Label label : held.overlay().labels()) {
                BlockPos at = label.at();
                Gizmos.billboardText(label.text(),
                                new Vec3(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5),
                                TextGizmo.Style.forColorAndCentered(label.color())
                                        .withScale(LABEL_SCALE))
                        .setAlwaysOnTop();
            }
        }
    }

    /** A group's paint, rebuilt per frame — three ints into the record gizmos already take. */
    private static GizmoStyle style(int stroke, float strokeWidth, int fill) {
        if (fill != 0 && stroke != 0) {
            return GizmoStyle.strokeAndFill(stroke, strokeWidth, fill);
        }
        if (fill != 0) {
            return GizmoStyle.fill(fill);
        }
        return GizmoStyle.stroke(stroke, strokeWidth);
    }

    /**
     * A face as drawable geometry: a slab {@link #FACE_THICKNESS} thick lying just inside its
     * cell against the face's plane (a hair short of it — see {@link #FACE_GAP}), its in-plane
     * extent grown by the same outset the cell boxes use so neighbouring faces of one membrane
     * meet instead of leaving hairline gaps.
     */
    private static AABB faceSlab(CellOverlayPayload.Face face) {
        BlockPos cell = face.cell();
        Direction side = Direction.from3DDataValue(face.side());
        // The face's plane sits at the cell's max side for positive directions, min side for
        // negative — and the slab retreats into the cell from it, so it stays on its own side.
        boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double near;
        double far;
        if (positive) {
            far = 1 - FACE_GAP;
            near = far - FACE_THICKNESS;
        } else {
            near = FACE_GAP;
            far = near + FACE_THICKNESS;
        }
        return switch (side.getAxis()) {
            case X -> new AABB(cell.getX() + near, cell.getY() - CELL_OUTSET,
                    cell.getZ() - CELL_OUTSET, cell.getX() + far,
                    cell.getY() + 1 + CELL_OUTSET, cell.getZ() + 1 + CELL_OUTSET);
            case Y -> new AABB(cell.getX() - CELL_OUTSET, cell.getY() + near,
                    cell.getZ() - CELL_OUTSET, cell.getX() + 1 + CELL_OUTSET,
                    cell.getY() + far, cell.getZ() + 1 + CELL_OUTSET);
            case Z -> new AABB(cell.getX() - CELL_OUTSET, cell.getY() - CELL_OUTSET,
                    cell.getZ() + near, cell.getX() + 1 + CELL_OUTSET,
                    cell.getY() + 1 + CELL_OUTSET, cell.getZ() + far);
        };
    }
}
