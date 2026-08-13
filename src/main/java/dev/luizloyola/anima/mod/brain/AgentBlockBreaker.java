package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.MiningSpeed;
import dev.luizloyola.anima.core.brain.act.ToolChoice;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.mod.body.AgentAttributes;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The {@link BlockBreaker} port over a live {@link AgentBody} — vanilla-fidelity block breaking
 * without a {@code Player}: the survival player's progress formula (hardness, the HELD stack's
 * destroy speed, the correct-tool divisor — a bare hand takes ~3s on a log), every factor
 * {@link MiningSpeed} adds (Haste, Mining Fatigue, Efficiency, water over the eyes, mid-air), the
 * shared crack animation, an arm swing, real drops, and the player's 0.005 exhaustion per block onto
 * {@link AgentBody#metabolism()}.
 *
 * <p>Owned and ticked by the body ({@link AgentBody#serverAiStep()}), exposed to the brain as a port
 * by the {@link BrainDriver}. Every tick mid-break re-validates the world — a swapped block or a
 * body out of reach fails the break — and re-reads the held item, so a mid-break tool swap changes
 * speed like a player's.
 */
public final class AgentBlockBreaker implements BlockBreaker {
    /**
     * Arm's reach in blocks (eye to block center) for a body that declares no
     * {@code block_interaction_range} — the survival player's own default, for a body that skipped
     * {@link AgentAttributes#mining}.
     */
    private static final double DEFAULT_REACH = 4.5;
    /** Vanilla's per-block exhaustion for breaking (verified against the player mining path). */
    private static final float EXHAUSTION_PER_BLOCK = 0.005F;

    private final AgentBody person;

    private BreakState state = BreakState.IDLE;
    private @Nullable BlockPos target;
    /** The block we started on — a different block appearing at {@link #target} fails the break. */
    private @Nullable BlockState begunOn;
    /** Accumulated progress 0..1 (vanilla's destroy-progress scale). */
    private float progress;
    /** Last crack stage broadcast (0–9), or -1 when none is showing. */
    private int sentStage = -1;

    public AgentBlockBreaker(AgentBody person) {
        this.person = person;
    }

    @Override
    public boolean begin(Pos cell) {
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        Level level = person.level();
        BlockState blockState = level.getBlockState(pos);
        if (blockState.isAir() || blockState.getDestroySpeed(level, pos) < 0 || !inReach(pos)
                || !LevelProbe.armPathClear(level, person.entity().getEyePosition(), pos)) {
            return false; // includes a blocked arm path: no breaking logs through the canopy
        }
        wieldBestFor(blockState);
        clearCrack();
        this.target = pos;
        this.begunOn = blockState;
        this.progress = 0.0F;
        this.state = BreakState.BREAKING;
        return true;
    }

    @Override
    public @Nullable Pos obstruction(Pos target) {
        Level level = person.level();
        Vec3 from = person.entity().getEyePosition();
        BlockPos targetPos = new BlockPos(target.x(), target.y(), target.z());
        Vec3 to = Vec3.atCenterOf(targetPos);
        // The same march armPathClear refuses by — half-block strides from the real eyes —
        // returning the first striking cell instead of a verdict, so a caller can cure the
        // refusal instead of guessing at it.
        int steps = (int) Math.ceil(from.distanceTo(to) * 2.0);
        for (int i = 1; i < steps; i++) {
            BlockPos cell = BlockPos.containing(from.lerp(to, i / (double) steps));
            if (cell.equals(targetPos) || !level.isLoaded(cell)) {
                continue;
            }
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                return new Pos(cell.getX(), cell.getY(), cell.getZ());
            }
        }
        return null;
    }

    /** One tick of arm work, from {@link AgentBody#serverAiStep()}; a no-op unless mid-break. */
    public void tick() {
        if (state != BreakState.BREAKING) {
            return;
        }
        Level level = person.level();
        BlockState now = level.getBlockState(target);
        if (now.getBlock() != begunOn.getBlock() || !inReach(target)
                || !LevelProbe.armPathClear(level, person.entity().getEyePosition(), target)) {
            fail(); // moved, block swapped, or something grew between arm and block
            return;
        }
        float hardness = now.getDestroySpeed(level, target);
        if (hardness < 0) {
            fail();
            return;
        }
        person.faceBlock(target); 
        progress += perTick(now, hardness);
        // Every tick, like a mining player (continueDestroyBlock does this): swing()'s own
        // guard restarts the animation at half duration — the player arm's mining cadence, owned by
        // vanilla — and only broadcasts on an actual restart, so this does not spam packets.
        person.entity().swing(InteractionHand.MAIN_HAND);
        if (progress >= 1.0F) {
            clearCrack();
            // The harvest check vanilla's player path applies before dropping: stone punched
            // bare-handed breaks, slowly, but yields nothing.
            ItemStack held = person.entity().getMainHandItem();
            boolean drops = !now.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(now);
            // Vanilla tool wear (Item.mineBlock): one durability per broken block of any
            // hardness. Damaging the VANILLA held stack is deliberate — the two-way equipment
            // mirror pulls the change back into the carried inventory, the source of truth.
            if (!held.isEmpty() && hardness > 0.0F) {
                held.hurtAndBreak(1, person.entity(), net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            }
            level.destroyBlock(target, drops, person.entity());
            person.metabolism().exhaust(EXHAUSTION_PER_BLOCK);
            state = BreakState.FINISHED;
            return;
        }
        int stage = Math.min(9, (int) (progress * 10.0F));
        if (stage != sentStage) {
            level.destroyBlockProgress(person.entity().getId(), target, stage);
            sentStage = stage;
        }
    }

    @Override
    public BreakState state() {
        return state;
    }

    @Override
    public void abort() {
        clearCrack();
        state = BreakState.IDLE;
    }

    /**
     * The wield step, run once per block begun: measure every carried stack against the block and
     * bring the pack's best into the hand — or empty it when nothing out-digs a fist (an axe
     * measures 1.0 on dirt, same as bare knuckles, so tools stay sheathed for dirt with no rule
     * about dirt anywhere). Ranking is {@link ToolChoice}'s.
     *
     * <p>Writes the CORE inventory only; an entity-side write would be stomped by the equipment
     * mirror. Stateless, so a tool that breaks mid-chop leaves the next {@code begin()} to re-rank.
     *
     * <p>Known limit: ranking is by the STACK's own speed, so Efficiency — which lands only once
     * equipped — cannot separate two otherwise-equal axes. The tie keeps whichever is in the hand.
     */
    private void wieldBestFor(BlockState blockState) {
        Inventory inv = person.inventory();
        HolderLookup.Provider registries = person.level().registryAccess();
        List<ToolChoice.Candidate> pack = new ArrayList<>();
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            dev.luizloyola.anima.core.inv.ItemStack core = inv.get(slot);
            if (core.isEmpty()) {
                continue;
            }
            ItemStack stack = ItemStacks.toVanilla(core, registries);
            pack.add(new ToolChoice.Candidate(
                    slot, stack.getDestroySpeed(blockState), stack.isCorrectToolForDrops(blockState)));
        }
        int heldSlot = Inventory.HOTBAR_START + inv.selectedSlot();
        int choice = ToolChoice.choose(pack, heldSlot,
                ItemStack.EMPTY.getDestroySpeed(blockState), blockState.requiresCorrectToolForDrops());
        if (choice == ToolChoice.BARE_HAND) {
            inv.stow();
        } else if (choice != ToolChoice.KEEP_HAND) {
            inv.wield(choice);
        }
    }

    /** The survival player's destroy-progress formula, minus nothing: speed / hardness / divisor. */
    private float perTick(BlockState blockState, float hardness) {
        ItemStack held = person.entity().getMainHandItem();
        boolean harvest = !blockState.requiresCorrectToolForDrops()
                || held.isCorrectToolForDrops(blockState);
        return miningSpeed(held, blockState) / hardness / (harvest ? 30.0F : 100.0F);
    }

    /**
     * Everything the world has to say about how fast this body mines, gathered off the entity and
     * combined by {@link MiningSpeed}.
     *
     * <p>Haste and Conduit Power arrive together through {@code MobEffectUtil} — the call
     * {@code LivingEntity} uses to shorten the swing ANIMATION, and that is how a hasted agent came to
     * swing visibly faster while mining at the old rate.
     */
    private float miningSpeed(ItemStack held, BlockState blockState) {
        LivingEntity self = person.entity();
        MobEffectInstance fatigue = self.getEffect(MobEffects.MINING_FATIGUE);
        return MiningSpeed.of(
                held.getDestroySpeed(blockState),
                attribute(self, Attributes.MINING_EFFICIENCY, 0.0),
                MobEffectUtil.hasDigSpeed(self)
                        ? MobEffectUtil.getDigSpeedAmplification(self) : MiningSpeed.ABSENT,
                fatigue == null ? MiningSpeed.ABSENT : fatigue.getAmplifier(),
                attribute(self, Attributes.BLOCK_BREAK_SPEED, 1.0),
                self.isEyeInFluid(FluidTags.WATER)
                        ? attribute(self, Attributes.SUBMERGED_MINING_SPEED, 0.2) : MiningSpeed.DRY,
                self.onGround());
    }

    /**
     * An attribute this body may not have declared: a consumer that never called
     * {@link AgentAttributes#mining} would otherwise take an {@code IllegalArgumentException} out of
     * {@code AttributeSupplier.getValue} mid-swing. The fallback is the vanilla player's default, so
     * an undeclared body mines like an unmodified one.
     */
    private static double attribute(LivingEntity self, Holder<Attribute> attribute, double whenAbsent) {
        return self.getAttributes().hasAttribute(attribute)
                ? self.getAttributeValue(attribute) : whenAbsent;
    }

    private boolean inReach(BlockPos pos) {
        // Vanilla's own geometry (eye to block centre) over vanilla's own number, so a reach
        // modifier on the body moves what the arm can touch — the constant was only ever the
        // player's default value written down.
        double reach = attribute(person.entity(), Attributes.BLOCK_INTERACTION_RANGE, DEFAULT_REACH);
        return person.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
    }

    private void fail() {
        clearCrack();
        state = BreakState.FAILED;
    }

    private void clearCrack() {
        if (sentStage >= 0) {
            person.level().destroyBlockProgress(person.entity().getId(), target, -1);
            sentStage = -1;
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * A swing in progress.
     *
     * <p>{@code progress} accumulates over many ticks, so losing it restarts the same log from
     * nothing — and the TASK that ordered the break survives a reload saying one is in flight, so a
     * forgotten breaker leaves a body waiting on a swing nobody is swinging. The flag and the
     * machine travel together; see
     * {@code docs/superpowers/specs/2026-08-03-persistence-design.md}.
     *
     * <p>{@code begunOn} is re-read from the world on restore rather than written down.
     */
    public record Swing(String state, @Nullable BlockPos target, float progress, int sentStage) {
    }

    /** What this breaker would need to go on swinging at the same block. */
    public Swing snapshot() {
        return new Swing(state.name(), target, progress, sentStage);
    }

    /**
     * Puts a swing back. The blockstate it began against is re-read here rather than carried: the
     * world is restored too, so a fresh read is the same answer and cannot be stale.
     */
    public void restore(Swing swing) {
        this.state = BreakState.valueOf(swing.state());
        this.target = swing.target();
        this.progress = swing.progress();
        this.sentStage = swing.sentStage();
        this.begunOn = this.target == null ? null : person.level().getBlockState(this.target);
    }
}
