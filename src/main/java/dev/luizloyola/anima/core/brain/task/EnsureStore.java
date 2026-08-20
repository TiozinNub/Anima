package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * BE at a store — {@link EnsureTable} with a different block, and the same two ways of getting
 * there priced against each other:
 *
 * <ul>
 *   <li><b>Walk to a known one</b>, priced at the distance, so a settlement converges on the chests
 *       it already has.</li>
 *   <li><b>Make one and put it down</b>, a flat {@link #PLACE_COST}: obtain the item (logs →
 *       planks → chest, or one already in the pack), place it, and claim it for the party.</li>
 * </ul>
 *
 * <p><b>Where a made one goes is settlement policy</b> (decision: Luiz, 2026-08-20): beside
 * something the party already owns when one is inside {@code stores.found_radius}, and only
 * underfoot when nothing is. A chest dropped wherever a pack happened to fill is a chest nobody
 * ever walks past again, and a forest full of them is not a settlement.
 */
public final class EnsureStore implements AchieveTask {

    /** The walk-vs-build breakeven, in the blocks-flavoured currency every method prices in. */
    public static final double PLACE_COST = 32.0;

    /**
     * Where the caller wants the store, or null for "any store will do". A hint narrows both
     * methods: only stores near it count as arriving, and a new one is built beside it rather than
     * beside the body. It is how a project's yard is told apart from the chest a settler happens to
     * be standing next to.
     */
    private final @Nullable Pos hint;

    private final List<Method> methods = List.of(new WalkToKnown(), new MakeAndPlace());

    public EnsureStore() {
        this(null);
    }

    public EnsureStore(@Nullable Pos hint) {
        this.hint = hint;
    }

    /** The hint this was built with, for the codec. */
    public @Nullable Pos hint() {
        return hint;
    }

    @Override
    public boolean satisfied(BrainContext ctx) {
        if (hint == null) {
            return Store.standingAtOne(ctx);
        }
        // Standing in a chest that is not the yard is not being at the yard: without this a hauler
        // would empty the project's wood into whatever they happened to be beside.
        return Store.standingAtOne(ctx) && yardNear(ctx, hint).isPresent();
    }

    /**
     * The nearest store to {@code where} that is close enough to BE the place asked for, in this
     * body's memory. Reuses {@code stores.found_radius} — the same number that decides whether a
     * new chest counts as part of a settlement rather than a camp of its own.
     */
    private static Optional<PoiMemory> yardNear(BrainContext ctx, Pos where) {
        double radius = ctx.profile().i(ProfileAspect.STORES_FOUND_RADIUS);
        return ctx.knowledge().all(Store.POI).stream()
                .filter(memory -> !ctx.knowledge().isAvoided(Store.POI, memory.anchor(),
                        ctx.percepts().time()))
                .filter(memory -> Store.distance(memory.anchor(), where) <= radius)
                .min(java.util.Comparator.comparingDouble(
                        memory -> Store.distance(memory.anchor(), where)));
    }

    /** The store this goal is willing to use, hint-aware — empty when none qualifies. */
    private Optional<PoiMemory> usable(BrainContext ctx) {
        return hint == null ? Store.nearestKnown(ctx) : yardNear(ctx, hint);
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "be at a store";
    }

    /** Walk into reach of a usable remembered store — the nearest one, or the yard. */
    final class WalkToKnown implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return usable(ctx).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return usable(ctx)
                    .map(known -> Store.distance(known.anchor(), ctx.percepts().position()))
                    .orElse(Double.MAX_VALUE);
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
            return nearestPartyPlace(ctx).isPresent() || spotBeside(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return PLACE_COST;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            if (hint != null) {
                return openTheYard(ctx);
            }
            Optional<PoiMemory> anchor = nearestPartyPlace(ctx);
            List<Task> steps = new ArrayList<>();
            Pos spot = null;
            if (anchor.isPresent()) {
                // Two DIFFERENT cells beside the bench: one to stand in, one to build in. The
                // first cut used one for both, so a settler walked into the spot and then tried
                // to put a chest where she was standing — which built the chest into her before
                // the placer learned to refuse, and livelocked the goal afterwards (in-world,
                // 2026-08-20).
                Pos stand = EnsureTable.WalkToKnown.standableBeside(anchor.get().anchor(), ctx);
                spot = freeBeside(ctx, anchor.get().anchor(), stand);
                Pos feet = ctx.percepts().position();
                boolean alreadyThere = feet.x() == stand.x() && feet.y() == stand.y()
                        && feet.z() == stand.z();
                if (spot != null && !alreadyThere) {
                    // Never walk to the cell you are standing in: the navigator answers PATHING to
                    // its own cell and never arrives, so the goal hangs there for ever rather than
                    // failing (in-world, 2026-08-20). Cheaper to not ask than to fix arrival
                    // tolerance from here.
                    steps.add(new GoTo(stand.x(), stand.y(), stand.z()));
                }
            }
            if (spot == null) {
                // Nothing free beside the anchor, or nothing known: build next to the body, where
                // spotBeside already refuses any cell somebody is standing in.
                spot = spotBeside(ctx);
            }
            steps.add(new ObtainItem(ItemSpec.anyOf(Set.of(Store.ITEM_ID)), 1, Set.of()));
            steps.add(new PlaceBlock(Store.ITEM_ID, spot.x(), spot.y(), spot.z()));
            // Communal, not owned: a container a settler places belongs to the PARTY, which is
            // 2a's ruling honoured by the first code that ever places one.
            steps.add(new FoundPlace(Store.POI, spot.x(), spot.y(), spot.z()));
            return steps;
        }

        @Override
        public String describe() {
            return hint == null ? "make a store and put it down" : "open the yard";
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
            Pos ground = groundNear(ctx, hint);
            Pos stand = EnsureTable.WalkToKnown.standableBeside(ground, ctx);
            List<Task> steps = new ArrayList<>();
            Pos feet = ctx.percepts().position();
            if (!(feet.x() == stand.x() && feet.y() == stand.y() && feet.z() == stand.z())) {
                steps.add(new GoTo(stand.x(), stand.y(), stand.z()));
            }
            steps.add(new ObtainItem(ItemSpec.anyOf(Set.of(Store.ITEM_ID)), 1, Set.of()));
            steps.add(new PlaceBlock(Store.ITEM_ID, ground.x(), ground.y(), ground.z()));
            steps.add(new FoundPlace(Store.POI, ground.x(), ground.y(), ground.z()));
            return steps;
        }

        /**
         * The hint itself when a chest can stand there, else the closest cell around it that can —
         * two rings, nearest first. Falls back to the hint, which lets the placer refuse and the
         * round re-derive rather than inventing somewhere far away.
         */
        private static Pos groundNear(BrainContext ctx, Pos wanted) {
            BlockProbe probe = ctx.percepts().blocks();
            if (probe.at(wanted.x(), wanted.y(), wanted.z()) == BlockKind.AIR
                    && probe.at(wanted.x(), wanted.y() - 1, wanted.z()) != BlockKind.AIR
                    && !PlaceBlock.occupied(ctx, wanted)) {
                return wanted;
            }
            for (int ring = 1; ring <= 2; ring++) {
                for (int[] side : SIDES) {
                    Pos cell = new Pos(wanted.x() + side[0] * ring, wanted.y(),
                            wanted.z() + side[1] * ring);
                    if (probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.AIR
                            && probe.at(cell.x(), cell.y() - 1, cell.z()) != BlockKind.AIR
                            && !PlaceBlock.occupied(ctx, cell)) {
                        return cell;
                    }
                }
            }
            return wanted;
        }

        /**
         * The nearest place this body knows the party owns, within {@code stores.found_radius} — a
         * workbench today, a hall when there are halls. Stores are excluded on purpose: one near
         * enough to build beside is one {@link WalkToKnown} would have reached more cheaply.
         */
        private static Optional<PoiMemory> nearestPartyPlace(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            double radius = ctx.profile().i(ProfileAspect.STORES_FOUND_RADIUS);
            return ctx.knowledge().nearest(Workbench.POI, here)
                    .filter(known -> Store.distance(known.anchor(), here) <= radius);
        }

        /**
         * A cell beside {@code anchor} that a chest can stand in and nobody is using — never
         * {@code stand}, which is where the body is about to be. Null when the bench is boxed in,
         * which sends the caller back to building next to itself.
         */
        private static Pos freeBeside(BrainContext ctx, Pos anchor, Pos stand) {
            BlockProbe probe = ctx.percepts().blocks();
            for (int[] side : SIDES) {
                Pos cell = new Pos(anchor.x() + side[0], anchor.y(), anchor.z() + side[1]);
                if (cell.x() == stand.x() && cell.y() == stand.y() && cell.z() == stand.z()) {
                    continue;
                }
                if (probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.AIR
                        && probe.at(cell.x(), cell.y() - 1, cell.z()) != BlockKind.AIR
                        && !PlaceBlock.occupied(ctx, cell)) {
                    return cell;
                }
            }
            return null;
        }

        /**
         * An empty cell on solid ground beside the body — where a chest goes with nothing else to
         * put it near. Two rings out, nearest first; {@code null} when the body is bricked in,
         * which makes the method inapplicable rather than a doomed decomposition.
         */
        private static Pos spotBeside(BrainContext ctx) {
            BlockProbe probe = ctx.percepts().blocks();
            Pos feet = ctx.percepts().position();
            for (int ring = 1; ring <= 2; ring++) {
                for (int[] side : SIDES) {
                    int x = feet.x() + side[0] * ring;
                    int z = feet.z() + side[1] * ring;
                    Pos cell = new Pos(x, feet.y(), z);
                    if (probe.at(x, feet.y(), z) == BlockKind.AIR
                            && probe.at(x, feet.y() - 1, z) != BlockKind.AIR
                            && !PlaceBlock.occupied(ctx, cell)) {
                        return cell;
                    }
                }
            }
            return null;
        }
    }

    /** The eight horizontal neighbours, sides first — a chest in a corner is awkward to reach. */
    private static final int[][] SIDES = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
}
