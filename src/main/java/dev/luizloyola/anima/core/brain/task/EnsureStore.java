package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * BE at a store of the body's {@link Depot} — {@link EnsureTable} with a different block, and two
 * ways of getting there:
 *
 * <ul>
 *   <li><b>Walk to a known one</b> in the depot's area, so a settlement converges on the chests it
 *       already has.</li>
 *   <li><b>Make one and put it down</b> at the depot's hint, only while none is known: obtain the
 *       item (logs → planks → chest, or one already in the pack), place it, and claim it for the
 *       party.</li>
 * </ul>
 *
 * <p><b>A store is only ever made at the depot</b> (decision: Luiz, 2026-09-30): no base, no
 * offloading. The depot is asked afresh every time, so a home that grows or a first chest that
 * moves the hint is seen at once, and a saved plan holds nothing to go stale.
 */
public final class EnsureStore implements AchieveTask {

    /** The price of building one, in the blocks-flavoured currency every method prices in. */
    public static final double PLACE_COST = 32.0;

    private final List<Method> methods = List.of(new WalkToKnown(), new MakeAndPlace());

    @Override
    public boolean satisfied(BrainContext ctx) {
        // The store at hand must be the depot's: without this a hauler would empty the project's
        // wood into whatever they happened to be beside.
        Optional<Depot.Site> site = ctx.depot();
        return site.isPresent() && Store.standingAtOne(ctx)
                && Store.atHand(ctx).filter(site.get()::holds).isPresent();
    }

    /** The nearest of the party's stores in the depot's area, in this body's memory. */
    private static Optional<PoiMemory> usable(BrainContext ctx) {
        Optional<Depot.Site> site = ctx.depot();
        if (site.isEmpty()) {
            return Optional.empty();
        }
        Pos feet = ctx.percepts().position();
        return Store.ours(ctx).stream()
                .filter(memory -> !ctx.knowledge().isAvoided(Store.POI, memory.anchor(),
                        ctx.percepts().time()))
                .filter(memory -> site.get().holds(memory.anchor()))
                .min(Comparator.comparingDouble(memory -> Store.distance(memory.anchor(), feet)));
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "be at a store";
    }

    /** Walk into reach of a remembered store in the depot's area. */
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
            // The party's first chest is everyone's. Four settlers each opened their own on
            // 2026-08-25 because nothing asked this; the loser of the race now falls to
            // WalkToKnown instead. A chest found FULL is avoided, so usable stops seeing it and a
            // second one is wanted again — the one case where another store is the right answer.
            return ctx.depot().isPresent() && usable(ctx).isEmpty();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return PLACE_COST;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return openAStore(ctx);
        }

        @Override
        public String describe() {
            return "open a store";
        }

        /**
         * A chest at the depot's hint, walked to first.
         *
         * <p><b>The hint is a hint</b> (decision: Luiz): the cell itself may be water, occupied or
         * floorless, so the chest goes on the nearest ground that will hold it.
         */
        private List<Task> openAStore(BrainContext ctx) {
            Pos ground = Ground.near(ctx, ctx.depot().orElseThrow().hint(), 2);
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
