package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.inv.CookedForms;
import dev.luizloyola.anima.compat.inv.FoodValues;
import dev.luizloyola.anima.compat.nav.LevelGrid;
import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.ConfinementCadence;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.compat.agent.Fighters;
import dev.luizloyola.anima.compat.agent.Melee;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import net.minecraft.world.entity.LivingEntity;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Surroundings;
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
    /** The terrain sense — one {@link LevelGrid} over this person's level, shared with nothing: it
     *  holds the chunk under its last read, which bodies in different columns would thrash. */
    private final LevelGrid terrain;
    /** Where perceived beings come from: the sensor is the body owner's to run, not the percept's
     *  to reach for. Handed in so this adapter never has to know what kind of sensor it is. */
    private final Supplier<List<Being>> beings;
    /** The last confinement answer; {@code null} until first asked. */
    private @Nullable Confinement confinement;
    /** When the next survey is owed, and this body's own slot to owe it on. */
    private @Nullable ConfinementCadence cadence;
    /** When to ask how the space this body stands in opens, and the last answer. */
    private final EnclosureWatch enclosure;

    /** {@code person.tickCount} at which {@link #dropsCache} was last filled. */
    private int dropsQueriedAt;
    /** Last drop scan, reused within the budget window; {@code null} until the first query. */
    private @Nullable List<Drop> dropsCache;

    public AgentPercepts(AgentBody person, Supplier<List<Being>> beings) {
        this.person = person;
        this.beings = beings;
        this.blocks = new LevelProbe(person.entity());
        this.terrain = new LevelGrid(person.level());
        this.enclosure = new EnclosureWatch(person);
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

    /** The same world in the pathfinder's vocabulary — see {@link LevelGrid}. */
    @Override
    public NavGrid terrain() {
        return this.terrain;
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

    @Override
    public boolean attackedLately(BeingId who) {
        return this.person.beingSense().attackedLately(who);
    }

    /** Read live off the body, but only for a being this body perceives right now. */
    @Override
    public java.util.Optional<Combatant> combatant(BeingId who) {
        if (!(this.person.level() instanceof ServerLevel level) || !perceives(who)) {
            return java.util.Optional.empty();
        }
        LivingEntity body = AgentStriker.find(level, who);
        if (body == null || body.level() != level) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(Fighters.read(body, true));
    }

    /** This body, hitting with the weapon its arm would draw rather than whatever it holds. */
    @Override
    public java.util.Optional<Combatant> selfAsCombatant() {
        return selfAgainst(null);
    }

    /** Only for a being this body perceives: the arm would not know whom it was sizing up for. */
    @Override
    public java.util.Optional<Combatant> selfAsCombatant(BeingId against) {
        if (!(this.person.level() instanceof ServerLevel level) || !perceives(against)) {
            return selfAsCombatant();
        }
        return selfAgainst(AgentStriker.find(level, against));
    }

    private java.util.Optional<Combatant> selfAgainst(@org.jspecify.annotations.Nullable LivingEntity target) {
        Combatant body = Fighters.read(this.person.entity(), this.person.metabolism().canSprint());
        if (body == null) {
            return java.util.Optional.empty();
        }
        Melee.Hit hit = this.person.striker().bestHit(target);
        // In a standing fight its blows land the held part of a reaction after each charge.
        double hold = this.person.profile().i(ProfileAspect.COMBAT_REACTION_HOLD_TICKS);
        double perSecond = hit.perSecond() > 0.0
                ? 20.0 / (20.0 / hit.perSecond() + hold) : 0.0;
        return java.util.Optional.of(new Combatant(body.health(), body.maxHealth(), body.armor(),
                body.toughness(), hit.damage(), perSecond, body.pace(), 0.0, 0.0));
    }

    @Override
    public boolean batteringLately(BeingId who) {
        return this.person.level() instanceof ServerLevel level
                && BreakIns.lately(who.value(), level.getGameTime(),
                        this.person.profile().i(ProfileAspect.SENSES_LINGER_TICKS))
                && perceives(who);
    }

    /** How long a line of sight is taken as read — the sense's own re-check pace, not every tick. */
    private static final int REACH_TICKS = 10;

    /** One reading of {@link #reaches}, and when. */
    private record Reach(boolean reaches, long at) {
    }

    private final java.util.Map<BeingId, Reach> reach = new java.util.HashMap<>();

    /**
     * Vanilla's own {@code hasLineOfSight}, from the being to this body: what a mob targets along,
     * an arrow flies along and a creeper keeps its fuse lit along.
     */
    @Override
    public boolean reaches(BeingId who) {
        if (!(this.person.level() instanceof ServerLevel level)) {
            return true;
        }
        long now = level.getGameTime();
        Reach known = this.reach.get(who);
        if (known != null && now - known.at() < REACH_TICKS) {
            return known.reaches();
        }
        LivingEntity body = AgentStriker.find(level, who);
        boolean reaches = body == null || body.level() != level
                || body.hasLineOfSight(this.person.entity());
        this.reach.values().removeIf(old -> now - old.at() >= REACH_TICKS);
        this.reach.put(who, new Reach(reaches, now));
        return reaches;
    }

    private boolean perceives(BeingId who) {
        for (Being being : beings()) {
            if (being.id().equals(who)) {
                return true;
            }
        }
        return false;
    }

    /** Delegates to the sensor's own guardrail memory. */
    @Override
    public boolean calledLately(BeingId whom) {
        return this.person.beingSense().calledLately(whom);
    }

    /** The overworld game clock — the same one knowledge timestamps carry. */
    @Override
    public long time() {
        return this.person.level().getGameTime();
    }

    @Override
    public java.util.Optional<Surroundings> surroundings() {
        return java.util.Optional.of(SurroundingsReader.of(this.person.entity()));
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
     * <p>So: its own survey, never waiting for a reason to ask — but on {@link ConfinementCadence}
     * rather than a plain timer, because a plain timer made this 96% of the server thread on a
     * 51-body world (2026-08-18). Two things were wrong with it: every body asked on the SAME tick,
     * since the period keyed off a tick count that starts at zero for everything in a chunk load;
     * and a free body re-proved its freedom as often as a trapped one re-tested its prison.
     *
     * <p>What did NOT change: the verdict, and the second-long cadence for a body that is shut in.
     */
    @Override
    public Confinement confinement() {
        int now = this.person.entity().tickCount;
        ConfinementCadence cadence = cadence();
        if (!cadence.due(now)) {
            return this.confinement == null ? Confinement.NONE : this.confinement;
        }
        this.confinement = this.person.level() instanceof ServerLevel level
                ? survey(level)
                : Confinement.NONE;
        cadence.ran(now, this.confinement);
        return this.confinement;
    }

    @Override
    public Enclosure enclosure() {
        return this.enclosure.current();
    }

    /** Asks about the space this body stands in when there is reason to — the brain's tick. */
    void tickEnclosure() {
        this.enclosure.tick(beings());
    }

    @Override
    public boolean strandedHere() {
        return this.person.level() instanceof ServerLevel level
                && this.person.setbacks().enclosed(position(), level.getGameTime());
    }

    /**
     * The ordinary survey, and a wider one when stranded walks say the body may be shut in
     * something the ordinary box cannot see whole — a crevice, a cave pocket — or in somewhere whose
     * only way out is a long swim, a ledge over a flooded channel. A wider look that finds a way
     * out, with room to stand along it, clears the evidence: the body was not shut in, or has got
     * out (Luiz, 2026-09-26). About 8 ms a look in the crevice, once a second while it climbs out.
     */
    private Confinement survey(ServerLevel level) {
        MoveCapabilities body = MoveCapabilities.of(this.person.profile());
        BlockPos feet = this.person.blockPosition();
        Confinement near = PathfinderService.surveyFrom(level, feet, body);
        if (near.sealed()) {
            return near;
        }
        Pos at = new Pos(feet.getX(), feet.getY(), feet.getZ());
        if (!this.person.setbacks().enclosed(at, level.getGameTime())) {
            return near;
        }
        Confinement wide = PathfinderService.surveyWide(level, feet, body);
        if (!wide.sealed()) {
            this.person.setbacks().free();
            return near;
        }
        if (this.confinement == null || !this.confinement.sealed()) {
            this.person.journal().record(Category.BRAIN, "enclosed", "walks from here keep "
                    + "failing; shut in a pocket of " + wide.cells() + " cells");
        }
        return wide;
    }

    /**
     * Built on first use rather than in the constructor: an agent's id is not resolved for an
     * entity's first tick or two, and a slot seeded from an unresolved one would put every body
     * that loaded together back on the tick this exists to spread them off.
     */
    private ConfinementCadence cadence() {
        if (this.cadence == null) {
            this.cadence = new ConfinementCadence(
                    this.person.agentId().value().getLeastSignificantBits());
        }
        return this.cadence;
    }

    @Override
    public double eyeHeight() {
        return this.person.entity().getEyeHeight();
    }

    @Override
    public double crouchedEyeHeight() {
        return this.person.entity().getDimensions(Pose.CROUCHING).eyeHeight();
    }

    /** The same attribute the breaker measures reach by, with the same player default. */
    @Override
    public double reach() {
        return AgentBlockBreaker.attribute(this.person.entity(),
                Attributes.BLOCK_INTERACTION_RANGE, AgentBlockBreaker.DEFAULT_REACH);
    }
}
