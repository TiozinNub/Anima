package dev.luizloyola.anima.compat.inv;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jspecify.annotations.Nullable;

/**
 * Lids, creaks and the vibration bus — what a container does when somebody reaches into it, for
 * bodies that are not players.
 *
 * <h2>Why vanilla's own counter is unusable</h2>
 *
 * <p>{@code ChestBlockEntity.startOpen} takes a {@code Player}, and there is no player here. Worse,
 * {@code incrementOpeners} schedules {@code recheckOpeners} five ticks out, and that recount works
 * by scanning an AABB for real players with the container menu open — so a synthetic increment is
 * slammed back to zero on the first recheck and fires a phantom close. A body reaching in therefore
 * owns the lid outright: nothing is ever scheduled against it and it stays where it is put.
 *
 * <h2>Why the count, and why it is not the whole count</h2>
 *
 * <p>Two settlers at one chest, and the first to finish would shut the lid on the second. Held per
 * cell, so the lid drops when the last of them steps back.
 *
 * <p><b>Ours is only ever half the total.</b> Vanilla's counter still holds the PLAYERS, and the two
 * cannot be merged — so our count going 0 → 1 does not mean the container opened, and a creak fired
 * on that edge is a lie whenever somebody was already in there. The creak rides the edge of
 * {@code others + ours} instead; see {@link #flips} and {@link #othersHolding}.
 *
 * <p>Transient and server-thread-only. A lid is a client-side animation and a reloaded world draws
 * every chest shut, so nothing is persisted; {@link #forget()} on server stop keeps a count leaked
 * by a body that died mid-reach from outliving the session that made it.
 */
public final class Lids {

    /** How many bodies are holding each cell open. Absent means shut. */
    private static final Map<GlobalPos, Integer> HELD = new HashMap<>();

    private Lids() {
    }

    /** One more body reaching in. The creak fires only if the container was shut to begin with. */
    public static void open(Level level, BlockPos pos, @Nullable Entity by) {
        BlockState state = level.getBlockState(pos);
        GlobalPos key = key(level, pos, state);
        int before = HELD.getOrDefault(key, 0);
        int others = othersHolding(level, pos);
        HELD.put(key, before + 1);
        signal(level, pos, state, others + before + 1);
        if (flips(others, before, before + 1)) {
            playSound(level, pos, state, true);
            level.gameEvent(by, GameEvent.CONTAINER_OPEN, pos);
        }
    }

    /**
     * One fewer. Closing a cell nobody opened does nothing, which is what makes the caller's
     * pairing cheap: a transfer may shut a lid its own open was refused.
     *
     * <p><b>Not reach-gated, unlike everything else a body does to a container.</b> A settler
     * shoved away from a chest mid-errand still has to be able to drop the lid it lifted, or it
     * hangs open until the chunk reloads.
     */
    public static void close(Level level, BlockPos pos, @Nullable Entity by) {
        BlockState state = level.getBlockState(pos);
        GlobalPos key = key(level, pos, state);
        Integer before = HELD.get(key);
        if (before == null) {
            return;
        }
        int after = Math.max(0, before - 1);
        if (after > 0) {
            HELD.put(key, after);
        } else {
            HELD.remove(key);
        }
        int others = othersHolding(level, pos);
        signal(level, pos, state, others + after);
        if (flips(others, before, after)) {
            playSound(level, pos, state, false);
            level.gameEvent(by, GameEvent.CONTAINER_CLOSE, pos);
        }
    }

    /** Drops every held lid, for a server shutting down. */
    public static void forget() {
        HELD.clear();
    }

    /**
     * Whether our holders going from {@code before} to {@code after} flips the container's WHOLE
     * open state, given {@code others} already holding it. The creak, the game event and the lid
     * all ride this rather than our own count, because ours is only part of the total.
     */
    static boolean flips(int others, int before, int after) {
        return (others + before == 0) != (others + after == 0);
    }

    /**
     * Holders that are not ours. {@code ChestBlockEntity.getOpenCount} is vanilla's own opener
     * count — public because {@code TrappedChestBlock} reads it for its redstone signal, and it can
     * never double-count us, since nothing here touches {@code openersCounter}. It answers 0 for
     * anything that is not a chest, which is also the honest answer: nothing else exposes one, and a
     * barrel's {@code OPEN} blockstate cannot stand in because that blockstate is OUR signal too —
     * reading it back would have us counting ourselves. A player sharing a BARREL with a settler is
     * the residual, and it costs them one spurious creak.
     */
    private static int othersHolding(Level level, BlockPos pos) {
        return ChestBlockEntity.getOpenCount(level, pos);
    }

    /**
     * The cell a lid is counted under. <b>A double chest is two block entities and one lid</b>, so
     * both halves have to count into the same entry — counting them apart would let two settlers on
     * opposite halves shut the lid on each other, which is the whole reason the count exists.
     * Whichever half is asked, the lower of the pair is named.
     */
    private static GlobalPos key(Level level, BlockPos pos, BlockState state) {
        if (!state.hasProperty(ChestBlock.TYPE)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return GlobalPos.of(level.dimension(), pos);
        }
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
        return GlobalPos.of(level.dimension(), pos.compareTo(other) <= 0 ? pos : other);
    }

    /**
     * The lid itself. Block event 1 is read as "this many bodies have me open" by every vanilla
     * container that animates one — chest, trapped chest and ender chest through
     * {@code LidBlockEntity}, the shulker box through its own {@code openCount} — so it is sent
     * blind rather than gated on a type list: a block entity with no lid returns false from
     * {@code triggerEvent} and nothing happens.
     */
    private static void signal(Level level, BlockPos pos, BlockState state, int held) {
        level.blockEvent(pos, state.getBlock(), 1, held);
        if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            // A double chest is two block entities drawing one lid between them, each from its own
            // openness. Signalling only the half that was reached into hinges it open down the
            // middle.
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            level.blockEvent(other, level.getBlockState(other).getBlock(), 1, held);
        }
        if (state.hasProperty(BlockStateProperties.OPEN)) {
            // A barrel keeps its lid in the blockstate rather than a block entity. Capability
            // again rather than a name, so a modded barrel-alike opens for free.
            level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, held > 0),
                    Block.UPDATE_ALL);
        }
    }

    /**
     * The creak. <b>The one place here a type list is unavoidable</b>: a capability tells us a
     * container has a lid, never what it sounds like. Anything unrecognised — a modded crate —
     * gets the chest's, on the grounds that it is the sound a player expects from a box
     * (decision: Luiz).
     */
    private static void playSound(Level level, BlockPos pos, BlockState state, boolean opening) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (state.hasProperty(ChestBlock.TYPE)) {
            playChestSound(level, pos, state, opening);
            return;
        }
        SoundEvent sound;
        if (entity instanceof EnderChestBlockEntity) {
            sound = opening ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.ENDER_CHEST_CLOSE;
        } else if (entity instanceof ShulkerBoxBlockEntity) {
            sound = opening ? SoundEvents.SHULKER_BOX_OPEN : SoundEvents.SHULKER_BOX_CLOSE;
        } else if (state.hasProperty(BlockStateProperties.OPEN)) {
            sound = opening ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE;
        } else {
            sound = opening ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE;
        }
        play(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound);
    }

    /**
     * A double chest creaks ONCE, from the seam. Vanilla buys the "once" by skipping the LEFT half,
     * because its own path sounds both of them; this is called on whichever half a settler reached
     * into, exactly once, so it centres instead — skipping LEFT here would mean a settler standing
     * at the left of a double chest opened it in silence.
     */
    private static void playChestSound(Level level, BlockPos pos, BlockState state, boolean opening) {
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction joined = ChestBlock.getConnectedDirection(state);
            x += joined.getStepX() * 0.5;
            z += joined.getStepZ() * 0.5;
        }
        play(level, x, pos.getY() + 0.5, z,
                opening ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE);
    }

    /** Vanilla's own volume and pitch jitter for a container, so ours is indistinguishable. */
    private static void play(Level level, double x, double y, double z, SoundEvent sound) {
        level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, 0.5F,
                level.getRandom().nextFloat() * 0.1F + 0.9F);
    }
}
