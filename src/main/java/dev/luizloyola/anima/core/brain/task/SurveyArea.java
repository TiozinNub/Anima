package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.CrescentSampler;
import dev.luizloyola.anima.core.brain.knowledge.HorizonScanner;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.knowledge.SenseEvent;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.brain.knowledge.Survey;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Walk a box until you know what is in it.
 *
 * <p>A deliberate look round does <b>not</b> produce places: {@link Survey} emits
 * {@link SenseEvent.Type#GLIMPSED} events, which become {@link Sighting}s on a coarse grid, while
 * the individual anchors anything can be sent to are grown by the NEAR FIELD, out of the crescent
 * sampler, within {@code places.near_radius}. Hence a walk, not a spend.
 *
 * <p>Confidence in a cell comes from either:
 *
 * <ul>
 *   <li><b>Walked near</b> — individuated and remembered by the near field. Full confidence, and
 *       the only way to clear a cell something was glimpsed in.</li>
 *   <li><b>Looked at, and nothing was there</b> — a ray reached it and no glimpse of the kind
 *       sought landed in it. Confidence falls off with viewing distance: a fan of rays spreads, so
 *       far out a thing can sit between adjacent bearings.</li>
 * </ul>
 *
 * <p>Empty ground is therefore cleared by looking and woodland has to be walked. Nothing here knows
 * what it is looking for beyond a {@link PoiKind}, which the caller supplies.
 */
public final class SurveyArea implements PrimitiveTask {

    /**
     * Edge of one coverage cell, in blocks. Matched to the glimpse grid on purpose — a sighting
     * lands on an 8-block cell, so a finer coverage grid would be recording a precision the
     * evidence does not have.
     */
    public static final int CELL = 8;

    /** Confidence a cell needs before the box is considered known. Tuning knob. */
    public static final double ENOUGH = 0.5;

    /**
     * Block reads one tick of looking may spend. The body is standing still while it does this, so
     * it can afford more than a walking sense's wallet — but a full survey is on the order of fifty
     * thousand reads for a Person, and spending that in one tick is a visible stall.
     */
    public static final int READS_PER_TICK = 512;

    /** Walks at a cell before it is written off as unreachable and stops holding the box open. */
    public static final int WALK_TRIES = 2;

    /** Centre to corner of one cell — how much of a cell can lie nearer than its centre does. */
    private static final double CELL_REACH = CELL * Math.sqrt(2) / 2;

    private final Region area;
    /** What a glimpse would have to be of for a cell to be worth walking into. */
    private final PoiKind looking;

    private final int wide;
    private final int deep;
    /** Row-major over the cell grid: how well this cell is known, 0..1. */
    private final float[] confidence;
    /** Walks attempted at each cell, so an unreachable one cannot hold the survey open forever. */
    private final int[] tries;

    /** The cell being visited, or -1 between errands. */
    private int target = -1;
    private @Nullable GoTo walk;
    private @Nullable Survey survey;
    /** Where the in-flight survey is anchored — its occlusion is only coherent from one spot. */
    private @Nullable Pos standing;
    /** Where the last completed look was taken from, so the body does not survey twice on the spot. */
    private @Nullable Pos looked;
    private final List<SenseEvent> seen = new ArrayList<>();

    public SurveyArea(Region area, PoiKind looking) {
        this.area = area;
        this.looking = looking;
        this.wide = cellsAcross(area.max().x() - area.min().x() + 1);
        this.deep = cellsAcross(area.max().z() - area.min().z() + 1);
        this.confidence = new float[wide * deep];
        this.tries = new int[wide * deep];
    }

    private static int cellsAcross(int blocks) {
        return Math.max(1, (blocks + CELL - 1) / CELL);
    }

    public Region area() {
        return area;
    }

    /** What a glimpse must be of for a cell to need walking. */
    public PoiKind looking() {
        return looking;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        // The near field is always on, so this runs every tick whatever leg of the loop we are on.
        markWalked(ctx);
        if (covered()) {
            return TaskStatus.SUCCESS;
        }
        if (survey != null) {
            return stepSurvey(ctx);
        }
        if (walk != null) {
            return stepWalk(ctx);
        }
        // Look before walking, always: walking first trudges to a corner it could have ruled out
        // standing still, and a box inside its own horizon gets walked whole for nothing.
        if (worthLookingHere(ctx)) {
            beginLooking(ctx);
            return TaskStatus.RUNNING;
        }
        return startNext(ctx);
    }

    /**
     * Whether a look from this spot would tell us anything new — never having looked at all, or
     * having moved a cell's width since the last one. Without the distance test a body that cannot
     * finish the box would stand still surveying the same view forever.
     */
    private boolean worthLookingHere(BrainContext ctx) {
        Pos lastLook = this.looked;
        return lastLook == null
                || horizontalDistance(lastLook, ctx.percepts().position()) >= CELL;
    }

    /**
     * Cells the body's near field is currently passing over are known — the <b>omnidirectional</b>
     * halo, not the wider aperture the crescent sweeps, or the sweep declares ground known that
     * nobody looked at and leaves a tree standing in a box somebody was told was clear.
     *
     * <p>The cell underfoot is known whatever the profile says: {@code places.near_radius} of zero
     * is legal (the test species does it), and such a body would otherwise learn nothing by walking
     * and, with no horizon, never finish.
     */
    private void markWalked(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        int near = CrescentSampler.nearRadius(ctx.profile());
        int underfoot = cellAt(here);
        if (underfoot >= 0) {
            confidence[underfoot] = 1.0f;
        }
        for (int cell = 0; cell < confidence.length; cell++) {
            if (confidence[cell] >= 1.0f) {
                continue;
            }
            if (horizontalDistance(here, centreOf(cell)) <= near) {
                confidence[cell] = 1.0f;
            }
        }
    }

    private TaskStatus stepWalk(BrainContext ctx) {
        GoTo leg = this.walk;
        TaskStatus status = leg.tick(ctx);
        switch (status) {
            case RUNNING -> {
                return TaskStatus.RUNNING;
            }
            case SUCCESS -> {
                // Arrived. The look happens next tick through the idle branch, the one place that
                // decides to look.
                this.walk = null;
                this.target = -1;
                return TaskStatus.RUNNING;
            }
            default -> {
                // Unreachable from here, this time. Written off after a couple of tries: a cell
                // behind a cliff would otherwise hold the box open forever.
                this.walk = null;
                if (target >= 0 && ++tries[target] >= WALK_TRIES) {
                    confidence[target] = (float) ENOUGH;
                    ctx.journal().record(Category.BRAIN, describe(),
                            "cannot reach " + at(centreOf(target)) + " — writing that corner off");
                }
                this.target = -1;
                return TaskStatus.RUNNING;
            }
        }
    }

    /**
     * Starts a look from where the body stands. Skipped entirely when this body cannot survey at
     * all (a reach no longer than its own near field is no reach) in which case the sweep is a
     * pure walk, which is slower and every bit as correct.
     */
    private void beginLooking(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        Survey attempt = new Survey(ctx.profile(), here);
        if (!attempt.possible()) {
            // No reach beyond the near field: this body sweeps on its feet alone. Recording the
            // spot anyway is what stops it asking the same question every tick forever.
            this.looked = here;
            return;
        }
        this.survey = attempt;
        this.standing = here;
        this.seen.clear();
    }

    private TaskStatus stepSurvey(BrainContext ctx) {
        Survey running = this.survey;
        BlockProbe probe = ctx.percepts().blocks();
        running.step(probe, READS_PER_TICK, seen);
        if (!running.done()) {
            return TaskStatus.RUNNING;
        }
        applyLook(ctx);
        this.looked = this.standing;
        this.survey = null;
        this.standing = null;
        this.target = -1;
        return TaskStatus.RUNNING;
    }

    /**
     * Files what the look found, and credits the ground it ruled out. Every glimpse is remembered
     * whatever kind it is; only glimpses of the kind being sought hold a cell open.
     */
    private void applyLook(BrainContext ctx) {
        Pos from = this.standing;
        long now = ctx.percepts().time();
        AgentKnowledge knowledge = ctx.knowledge();
        int maxPerKind = AgentKnowledge.maxPerKind(ctx.profile());
        Set<Integer> occupied = new HashSet<>();
        for (SenseEvent event : seen) {
            if (event.type() != SenseEvent.Type.GLIMPSED) {
                continue;
            }
            knowledge.glimpse(new Sighting(event.kind(), event.anchor(), from, now,
                    Sighting.Provenance.SURVEY), maxPerKind);
            if (event.kind().equals(looking)) {
                int cell = cellAt(event.anchor());
                if (cell >= 0) {
                    occupied.add(cell);
                }
            }
        }
        int horizon = HorizonScanner.radius(ctx.profile());
        // The survey's own blind ring: a look reports NOTHING closer than this — the near field
        // owns that range — so silence about a cell inside it is not evidence. Crediting it anyway
        // declared a box clear with an oak eleven blocks from the surveyor (2026-08-10).
        int blind = CrescentSampler.radius(ctx.profile());
        for (int cell = 0; cell < confidence.length; cell++) {
            if (occupied.contains(cell)) {
                continue; // something is there; only walking near it will say what.
            }
            double distance = horizontalDistance(from, centreOf(cell));
            // Measured to the cell's NEAREST corner, not its centre: a cell straddling the blind
            // ring is part unobservable. The live miss survived excluding the ring this way — a
            // tree nine blocks out in a cell whose centre was thirteen.
            if (distance > horizon || distance - CELL_REACH <= blind) {
                continue;
            }
            confidence[cell] = (float) Math.max(confidence[cell], 1.0 - distance / horizon);
        }
        seen.clear();
    }

    /** Heads for the least-known cell, nearest first among equals. */
    private TaskStatus startNext(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        int worst = -1;
        double worstScore = Double.MAX_VALUE;
        for (int cell = 0; cell < confidence.length; cell++) {
            if (confidence[cell] >= ENOUGH) {
                continue;
            }
            // Confidence first, distance only to break ties: a body should not cross the box for a
            // slightly emptier cell, and should not stay near home ignoring an unknown corner.
            double score = confidence[cell] * 1_000_000 + horizontalDistance(here, centreOf(cell));
            if (score < worstScore) {
                worstScore = score;
                worst = cell;
            }
        }
        if (worst < 0) {
            return TaskStatus.SUCCESS; // covered() disagreed for one tick; nothing left to do
        }
        this.target = worst;
        Pos centre = centreOf(worst);
        int y = ctx.percepts().blocks().surfaceY(centre.x(), centre.z());
        this.walk = new GoTo(centre.x(), y, centre.z());
        return TaskStatus.RUNNING;
    }

    private boolean covered() {
        for (float known : confidence) {
            if (known < ENOUGH) {
                return false;
            }
        }
        return true;
    }

    /** How many cells are known well enough — the progress everything else reports. */
    public int cellsKnown() {
        int known = 0;
        for (float value : confidence) {
            if (value >= ENOUGH) {
                known++;
            }
        }
        return known;
    }

    public int cells() {
        return confidence.length;
    }

    private Pos centreOf(int cell) {
        int cx = cell / deep;
        int cz = cell % deep;
        return new Pos(
                Math.min(area.min().x() + cx * CELL + CELL / 2, area.max().x()),
                area.min().y(),
                Math.min(area.min().z() + cz * CELL + CELL / 2, area.max().z()));
    }

    /** Which cell a world position falls in, or -1 when it is outside the box. */
    private int cellAt(Pos at) {
        int cx = (at.x() - area.min().x()) / CELL;
        int cz = (at.z() - area.min().z()) / CELL;
        if (at.x() < area.min().x() || at.z() < area.min().z() || cx >= wide || cz >= deep) {
            return -1;
        }
        return cx * deep + cz;
    }

    private static double horizontalDistance(Pos from, Pos to) {
        double dx = from.x() - to.x();
        double dz = from.z() - to.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String at(Pos p) {
        return "(" + p.x() + ", " + p.z() + ")";
    }

    @Override
    public void cancel(BrainContext ctx) {
        if (walk != null) {
            walk.cancel(ctx);
            walk = null;
        }
        survey = null;
        standing = null;
        seen.clear();
        target = -1;
    }

    @Override
    public String describe() {
        return "survey " + cellsKnown() + "/" + cells() + " of the ground at "
                + at(area.min());
    }

    @Override
    public String failureDetail() {
        return "could not get round the box at " + at(area.min());
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * How far the sweep got. The coverage is the progress — everything else is re-derivable.
     *
     * <p>A survey in flight is not carried: its rays are anchored to one spot and the
     * look costs a fraction of a second to take again. The cell being walked to is kept.
     */
    public record State(Region area, PoiKind looking, List<Float> confidence, List<Integer> tries,
                        int target) {
    }

    /** What this sweep would need to carry on where it left off. */
    public State snapshot() {
        List<Float> known = new ArrayList<>(confidence.length);
        for (float value : confidence) {
            known.add(value);
        }
        List<Integer> attempts = new ArrayList<>(tries.length);
        for (int value : tries) {
            attempts.add(value);
        }
        return new State(area, looking, known, attempts, target);
    }

    /** Puts the coverage back. A walk or a look in flight is re-decided on the next tick. */
    public SurveyArea restore(State state) {
        for (int cell = 0; cell < confidence.length && cell < state.confidence().size(); cell++) {
            confidence[cell] = state.confidence().get(cell);
        }
        for (int cell = 0; cell < tries.length && cell < state.tries().size(); cell++) {
            tries[cell] = state.tries().get(cell);
        }
        this.target = state.target();
        return this;
    }
}
