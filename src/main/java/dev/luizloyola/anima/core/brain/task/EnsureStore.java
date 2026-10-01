package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * BE at a store at the place asked for — {@link EnsureTable} with a different block, and two ways
 * of getting there:
 *
 * <ul>
 *   <li><b>Walk to a known one</b> near that place, so a settlement converges on the chests it
 *       already has.</li>
 *   <li><b>Make one and put it down</b> there, only while none is known: obtain the item (logs →
 *       planks → chest, or one already in the pack), place it, and claim it for the party.</li>
 * </ul>
 *
 * <p><b>A store is only ever made at the place asked for</b> (decision: Luiz, 2026-09-30): no
 * base, no offloading — see {@link dev.luizloyola.anima.core.store.Depot}.
 */
public final class EnsureStore implements AchieveTask {

    /** The price of building one, in the blocks-flavoured currency every method prices in. */
    public static final double PLACE_COST = 32.0;

    /**
     * Where the caller wants the store. Only stores near it count as arriving, and a new one is
     * built beside it: how a yard is told apart from the chest a settler happens to be next to.
     */
    private final Pos hint;

    private final List<Method> methods = List.of(new WalkToKnown(), new MakeAndPlace());

    public EnsureStore(Pos hint) {
        this.hint = Objects.requireNonNull(hint, "hint");
    }

    /** The hint this was built with, for the codec. */
    public Pos hint() {
        return hint;
    }

    @Override
    public boolean satisfied(BrainContext ctx) {
        // The store at hand must BE the yard: without this a hauler would empty the project's wood
        // into whatever they happened to be beside.
        double radius = ctx.profile().i(ProfileAspect.STORES_FOUND_RADIUS);
        return Store.standingAtOne(ctx)
                && Store.atHand(ctx).filter(at -> Store.distance(at, hint) <= radius).isPresent();
    }

    /**
     * The nearest store to {@code where} that is close enough to BE the place asked for, in this
     * body's memory. Reuses {@code stores.found_radius} — the same number that decides whether a
     * new chest counts as part of a settlement rather than a camp of its own.
     */
    private static Optional<PoiMemory> yardNear(BrainContext ctx, Pos where) {
        double radius = ctx.profile().i(ProfileAspect.STORES_FOUND_RADIUS);
        return Store.ours(ctx).stream()
                .filter(memory -> !ctx.knowledge().isAvoided(Store.POI, memory.anchor(),
                        ctx.percepts().time()))
                .filter(memory -> Store.distance(memory.anchor(), where) <= radius)
                .min(java.util.Comparator.comparingDouble(
                        memory -> Store.distance(memory.anchor(), where)));
    }

    /** The store this goal is willing to use — empty when none qualifies. */
    private Optional<PoiMemory> usable(BrainContext ctx) {
        return yardNear(ctx, hint);
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "be at a store";
    }

    /** Walk into reach of a remembered store at the yard. */
    final class WalkToKnown implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return usable(ctx).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            // Walking is not weighed against building — building is only on offer while no chest
            // is known — so the distance would only price the haul out.
            return usable(ctx).isPresent() ? 0.0 : Double.MAX_VALUE;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            PoiMemory known = usable(ctx).orElseThrow();
            Pos beside = EnsureTable.WalkToKnown.standableBeside(known.anchor(), ctx);
            return List.of(new GoTo(beside.x(), beside.y(), beside.z()));
        }

        @Override
        public String describe() {
            return "walk to a known store";
        }
    }

    /** Obtain a chest (crafting it from the pack if need be), place it, claim it for the party. */
    final class MakeAndPlace implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            // A yard is ONE place, and the party's first chest there is everyone's. Four settlers
            // each opened their own on 2026-08-25 because nothing asked this; the loser of the
            // race now falls to WalkToKnown instead. A yard chest found FULL is avoided, so
            // yardNear stops seeing it and a second one is wanted again — the one case where two
            // stores at a yard is the right answer.
            return yardNear(ctx, hint).isEmpty();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return PLACE_COST;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return openTheYard(ctx);
        }

        @Override
        public String describe() {
            return "open the yard";
        }

        /**
         * A chest at the place the project asked for — walked to first, since a yard is named
         * somewhere else by definition.
         *
         * <p><b>The hint is a hint</b> (decision: Luiz): the cell itself may be water, occupied or
         * floorless, so the chest goes on the nearest ground that will hold it. An operator who
         * pointed at bare rock still gets a yard, one block over, and the board readout names where
         * it actually went.
         */
        private List<Task> openTheYard(BrainContext ctx) {
            Pos ground = Ground.near(ctx, hint, 2);
            Pos stand = EnsureTable.WalkToKnown.standableBeside(ground, ctx);
            List<Task> steps = new ArrayList<>();
            Pos feet = ctx.percepts().position();
            // Never walk to your own cell: the navigator answers PATHING to it and never arrives
            // (in-world, 2026-08-20).
            if (!(feet.x() == stand.x() && feet.y() == stand.y() && feet.z() == stand.z())) {
                steps.add(new GoTo(stand.x(), stand.y(), stand.z()));
            }
            steps.add(new ObtainItem(ItemSpec.anyOf(Set.of(Store.ITEM_ID)), 1, Set.of()));
            steps.addAll(Ground.clearAndPlace(ctx, Store.ITEM_ID, ground));
            steps.add(new FoundPlace(Store.POI, ground.x(), ground.y(), ground.z()));
            return steps;
        }
    }

}
