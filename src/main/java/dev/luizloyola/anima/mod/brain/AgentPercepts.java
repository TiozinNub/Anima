package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.inv.CookedForms;
import dev.luizloyola.anima.compat.inv.FoodValues;
import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import net.minecraft.server.level.ServerLevel;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.need.Needs;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Percepts} <em>adapter</em>: what a {@link AgentBody}'s brain can sense, as
 * version-neutral views — the sensory twin of {@link AgentMover}/{@link AgentItemConsumer}.
 * Thin: the inventory and needs it exposes ARE the body's own core objects, no copies
 * to drift, and the food lookup is a lens over {@link FoodValues}/{@link CookedForms}, never a
 * snapshot.
 */
public final class AgentPercepts implements Percepts {
    /**
     * Drop-percept budget window, in ticks: an AABB entity query, not a plain field read, so
     * its result is held for this many ticks and re-run only when stale.
     */
    private static final int CACHE_TICKS = 5;

    private final AgentBody person;
    /**
     * Food knowledge as a lens over live game data — vanilla and modded foods alike: values from
     * the item registry ({@link FoodValues}), cooked forms from the recipe data ({@link CookedForms}).
     */
    private final FoodLookup foods;

    /** The block sense — one {@link LevelProbe} over this person's level and eyes, shared with
     *  nothing (the sensor builds its own): stateless views are cheap, aliasing is not. */
    private final LevelProbe blocks;
    /** Where perceived beings come from: the sensor is the body owner's to run, not the percept's
     *  to reach for. Handed in so this adapter never has to know what kind of sensor it is. */
    private final Supplier<List<Being>> beings;
    /**
     * How long a confinement answer is kept before it is asked again — see {@link #confinement()}.
     * One survey a second: cheap enough to pay unconditionally, prompt enough that a body which has
     * just cut its way out stops digging rather than carrying on out of habit.
     */
    private static final int CONFINEMENT_TICKS = 20;

    /** The last confinement answer and when it was taken; {@code null} until first asked. */
    private @Nullable Confinement confinement;
    private int confinementAskedAt;

    /** {@code person.tickCount} at which {@link #dropsCache} was last filled. */
    private int dropsQueriedAt;
    /** Last drop scan, reused within the budget window; {@code null} until the first query. */
    private @Nullable List<Drop> dropsCache;

    public AgentPercepts(AgentBody person, Supplier<List<Being>> beings) {
        this.person = person;
        this.beings = beings;
        this.blocks = new LevelProbe(person.entity());
        this.foods = new FoodLookup() {
            @Override
            public Optional<FoodValue> of(ItemStack stack) {
                return FoodValues.of(stack, person.entity().registryAccess());
            }

            @Override
            public Optional<FoodValue> cookedForm(ItemStack stack) {
                // Resolved per query, not captured: a AgentBody only ever ticks server-side, and the
                // recipe view must be the CURRENT one (CookedForms re-keys its cache on /reload).
                return CookedForms.of(stack, person.level().getServer());
            }
        };
    }

    /** The carried inventory — the same core object the body mirrors and persists. */
    @Override
    public Inventory inventory() {
        return this.person.inventory();
    }

    /** The body's metabolism, read as pressure — the brain never writes here. */
    @Override
    public Metabolism metabolism() {
        return this.person.metabolism();
    }

    /** Every gauge the body feels, read as pressure — the brain never writes here either. */
    @Override
    public Needs needs() {
        return this.person.needs();
    }

    /** What any given stack is worth as food — see {@link FoodValues}. */
    @Override
    public FoodLookup foods() {
        return this.foods;
    }

    /** Where the body actually stands, in whole blocks. */
    @Override
    public Pos position() {
        BlockPos pos = this.person.blockPosition();
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The world's blocks through the one {@link BlockProbe} vocabulary — the task-time re-walk sense. */
    @Override
    public BlockProbe blocks() {
        return this.blocks;
    }

    /**
     * Nearby dropped items as bare sightings, budgeted: the entity query runs at most once per
     * {@link #CACHE_TICKS} ticks, the same immutable list in between. The 16×8×16 box matches the
     * threat sense; consumers filter to their own work areas.
     */
    @Override
    public List<Drop> drops() {
        int now = this.person.entity().tickCount;
        if (this.dropsCache != null && now - this.dropsQueriedAt < CACHE_TICKS) {
            return this.dropsCache;
        }
        List<ItemEntity> items = this.person.level().getEntitiesOfClass(
                ItemEntity.class, this.person.entity().getBoundingBox().inflate(16.0, 8.0, 16.0));
        List<Drop> drops = new ArrayList<>(items.size());
        for (ItemEntity item : items) {
            if (!item.isAlive()) {
                continue;
            }
            BlockPos at = item.blockPosition();
            drops.add(new Drop(new Pos(at.getX(), at.getY(), at.getZ()),
                    BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(),
                    cellsTouchedBy(item.getBoundingBox())));
        }
        this.dropsQueriedAt = now;
        this.dropsCache = List.copyOf(drops);
        return this.dropsCache;
    }

    /**
     * The inclusive span of whole cells an entity box touches — the boundary's one chance to record
     * a drop's footprint, since {@code blockPosition()} throws it away.
     *
     * <p>Floor on both ends, so a box that merely grazes the next cell still counts it: the wider
     * footprint is the safe direction, a cell holding nothing costing one wasted read where a missed
     * one costs a gatherer walking at an unreachable item.
     */
    private static Region cellsTouchedBy(AABB box) {
        return new Region(
                new Pos(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ)),
                new Pos(Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ)));
    }

    /**
     * Everything they currently perceive — a read of the body's {@link BeingSense} state, not a
     * scan: the sight cone, the ears on the vibration bus, attention cadences, the identification
     * ladder, herds and the linger window all live in the sensor. {@code peers()} stays the
     * person-filtered view via the interface default.
     */
    @Override
    public List<Being> beings() {
        return this.beings.get();
    }

    /** The overworld game clock — the same one knowledge timestamps carry. */
    @Override
    public long time() {
        return this.person.level().getGameTime();
    }

    /**
     * Whether this body can get out of where it is — asked, not overheard.
     *
     * <p>Reading it off the navigator's last search was wrong exactly where it matters: a body
     * cutting its way out asks for one cell at a time inside its own prison, every such route
     * succeeds, and the drive that was digging switched off after every tread. A gate on
     * {@link MoveFailure#STRANDED} failed the same way — a settler sealed in a mound idled for
     * minutes without ever attempting a walk, so it never reported anything. Noticing you are
     * trapped cannot be conditional on having something to do; see
     * {@code docs/superpowers/specs/2026-08-11-stuck-and-escape-design.md}.
     *
     * <p>So: its own survey on a plain timer ({@link #CONFINEMENT_TICKS}), never waiting for a
     * reason to ask. The cost is FIXED — one bounded survey per body per second. If it shows up in a
     * profile, the levers are that constant and the capture box, not a cleverer gate.
     */
    @Override
    public Confinement confinement() {
        int now = this.person.entity().tickCount;
        if (this.confinement != null && now - this.confinementAskedAt < CONFINEMENT_TICKS) {
            return this.confinement;
        }
        this.confinementAskedAt = now;
        if (!(this.person.level() instanceof ServerLevel level)) {
            this.confinement = Confinement.NONE;
            return this.confinement;
        }
        this.confinement = PathfinderService.surveyFrom(level, this.person.blockPosition(),
                MoveCapabilities.of(this.person.profile()));
        return this.confinement;
    }
}
