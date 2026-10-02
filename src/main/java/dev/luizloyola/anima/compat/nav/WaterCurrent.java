package dev.luizloyola.anima.compat.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The push the water gives a body this tick, read the way vanilla's fluid interaction reads it:
 * the flow of every water cell the box reaches into, scaled down where the water is shallower than
 * {@link #SHALLOW} under the feet, summed, and for anything that is not a player normalised to
 * {@link #PUSH} — a fixed strength however gently the water moves.
 */
public final class WaterCurrent {
    /** Vanilla's water push a tick, after the flow is normalised. */
    public static final double PUSH = 0.014;
    /** Depth under which a cell's flow counts for less, in proportion. */
    private static final double SHALLOW = 0.4;
    /** Vanilla's fluid box is the body's, shrunk by this. */
    private static final double DEFLATE = 0.001;
    /** Below this squared length vanilla applies no push at all. */
    private static final double STILL_SQ = 1.0E-5;

    private WaterCurrent() {
    }

    /** The horizontal push, in blocks a tick; zero in still water, out of it, or for a player. */
    public static Vec3 push(Entity entity) {
        if (!entity.isPushedByFluid() || entity instanceof Player) {
            return Vec3.ZERO;
        }
        Level level = entity.level();
        AABB box = entity.getBoundingBox().deflate(DEFLATE);
        Vec3 flow = Vec3.ZERO;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(box.minX); x < Mth.ceil(box.maxX); x++) {
            for (int y = Mth.floor(box.minY); y < Mth.ceil(box.maxY); y++) {
                for (int z = Mth.floor(box.minZ); z < Mth.ceil(box.maxZ); z++) {
                    cell.set(x, y, z);
                    FluidState fluid = level.getFluidState(cell);
                    if (!fluid.is(FluidTags.WATER)) {
                        continue;
                    }
                    double depth = y + fluid.getHeight(level, cell) - box.minY;
                    if (depth < 0.0) {
                        continue;
                    }
                    Vec3 here = fluid.getFlow(level, cell);
                    flow = flow.add(depth < SHALLOW ? here.scale(depth) : here);
                }
            }
        }
        if (flow.lengthSqr() < STILL_SQ) {
            return Vec3.ZERO;
        }
        Vec3 push = flow.normalize().scale(PUSH);
        return new Vec3(push.x, 0.0, push.z);
    }
}
