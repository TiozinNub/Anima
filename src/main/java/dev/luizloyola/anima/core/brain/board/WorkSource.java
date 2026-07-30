package dev.luizloyola.anima.core.brain.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import java.util.List;
import java.util.Optional;

/**
 * The board's face toward the arbiter — the second demand source beside the instinct list. The
 * arbiter asks what work is on offer and reports how the engagement ended; everything else stays
 * behind this seam, where slice 3's shared board will plug in. The mod layer may wrap a source to
 * observe transitions.
 */
public interface WorkSource {
    /** A source with nothing to offer, ever — the no-board arbiter for tests and manual rigs. */
    WorkSource NONE = new WorkSource() {
        @Override
        public Optional<WorkItem> bestAvailable(BrainContext ctx) {
            return Optional.empty();
        }

        @Override
        public void claimed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
        }
    };

    /** The best unclaimed item currently on offer, if any. */
    Optional<WorkItem> bestAvailable(BrainContext ctx);

    /** The arbiter took the item — it is owed until completed, failed, or released. */
    void claimed(WorkItem item, BrainContext ctx);

    /** The item's root SUCCEEDED — the goal held; the board closes the item. */
    void completed(WorkItem item, BrainContext ctx);

    /** The item's root FAILED — the board unclaims it and paces the retry. */
    void failed(WorkItem item, BrainContext ctx);

    /**
     * The source's own slow thinking (posting, withdrawing, pacing retries) run once per brain
     * tick regardless of who is driving. Default no-op.
     */
    default void tick(BrainContext ctx) {
    }

    /**
     * A DRIVE (not an errand) just failed terminally: the instinct's root came back FAILED and it
     * is entering its fail cooldown. A hungry agent with no food in reach is a settlement that
     * needs a food project, and layer 3 is the layer that can post one.
     *
     * <p><b>Dormant.</b> Every board ignores it: the useful response needs a food
     * producer to exist first, and a project nobody can work is worse than no project. The seam
     * lands now so the reporting side is written once, at the place that knows the moment.
     */
    default void driveFailed(Instinct instinct, String detail, BrainContext ctx) {
    }

    /** One line for an operator, shown by the board command. Default says there is no board. */
    default String describe(BrainContext ctx) {
        return "no board";
    }

    /**
     * The same readout as {@link #describe}, free to take as many lines as it has to say — a source
     * composing several boards owes a row each. Defaults to the one line.
     */
    default List<String> describeLines(BrainContext ctx) {
        return List.of(describe(ctx));
    }
}
