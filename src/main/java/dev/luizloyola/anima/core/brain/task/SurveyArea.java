package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.Coverage;
import dev.luizloyola.anima.core.brain.knowledge.CoverageGrid;
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
 *   <li><b>Walked near</b> — individuated and remembered by the near field, a sub-cell square at a
 *       time and unioned across visits. The only way to clear a cell something was glimpsed in, and
 *       no longer full on one pass unless the whole cell was in range.</li>
 *   <li><b>Looked at, and nothing was there</b> — a ray reached it and no glimpse of the kind
 *       sought landed in it. Confidence falls off with viewing distance: a fan of rays spreads, so
 *       far out a thing can sit between adjacent bearings.</li>
 * </ul>
 *
 * <p>Empty ground is therefore cleared by looking and woodland has to be walked. Nothing here knows
 * what it is looking for beyond a {@link PoiKind}, which the caller supplies.
 */
public final class SurveyArea implements PrimitiveTask {

    /** Edge of one coverage cell, in blocks — defined by the grid every sweep now shares. */
    public static final int CELL = CoverageGrid.CELL;

    /** Confidence a cell needs before the box is considered known. */
    public static final double ENOUGH = CoverageGrid.ENOUGH;

    /**
     * Block reads one tick of looking may spend. A survey is fifty-odd thousand reads for a Person:
     * at 512 a look cost a hundred ticks of standing still, four times over per slice; at 2048, a
     * couple of dozen, about a third of a millisecond of main thread per body per tick. The
     * hand-driven {@code survey} command spends 4096 in one blocking burst; several bodies may be
     * looking at once.
     */
    public static final int READS_PER_TICK = 2048;

    /** Walks at a cell before it is written off as unreachable and stops holding the box open. */
    public static final int WALK_TRIES = 2;

    /** Centre to corner of one cell — how much of a cell can lie nearer than its centre does. */
    private static final double CELL_REACH = CELL * Math.sqrt(2) / 2;

    /**
     * What a look through OBSTRUCTED ground is worth, as a fraction of what the same look would
     * have been worth in the open. Visibility can only ever DISCOUNT the distance credit, never
     * inflate it — see the note where it is applied for what happened when it could.
     */
    private static final double OBSTRUCTED_VIEW = 0.5;

    private final Region area;
    /** What a glimpse would have to be of for a cell to be worth walking into. */
    private final PoiKind looking;
    private final Coverage coverage;

    private final int wide;
    private final int deep;
    /** What the body has covered on its feet — partial, unioned, and the part worth banking. */
    private final CoverageGrid walked;
    /**
     * What a look ruled out, 0..1 per cell. Per-look and never banked as such: a survey's occlusion
     * is only coherent from where it was taken, so a partial look is not a fact about the ground the
     * way a partial walk is. A look that carries a cell over ENOUGH banks the cell whole.
     */
    private final float[] looked;
    /** Walks attempted at each cell, so an unreachable one cannot hold the survey open forever. */
    private final int[] tries;

    /** The cell being visited, or -1 between errands. */
    private int target = -1;
    private @Nullable GoTo walk;
    private @Nullable Survey survey;
    /** Where the in-flight survey is anchored — its occlusion is only coherent from one spot. */
    private @Nullable Pos standing;
    /** Where the last completed look was taken from, so the body does not survey twice on the spot. */
    private @Nullable Pos lookedFrom;
    private final List<SenseEvent> seen = new ArrayList<>();

    public SurveyArea(Region area, PoiKind looking) {
        this(area, looking, java.util.Map.of());
    }

    /**
     * A sweep that starts already knowing some of its ground: {@code known} is corner → covered
     * squares, on the SAME grid this sweep lays. Corners rather than indices, so a caller whose grid
     * is offset gets no discount rather than a wrong one.
     */
    public SurveyArea(Region area, PoiKind looking, java.util.Map<Pos, Integer> known) {
        this(area, looking, known, Coverage.NONE);
    }

    /** As above, reporting what it covers to {@code coverage} as it covers it. */
    public SurveyArea(Region area, PoiKind looking, java.util.Map<Pos, Integer> known,
            Coverage coverage) {
        this.area = area;
        this.looking = looking;
        this.coverage = coverage;
        this.walked = new CoverageGrid(area);
        this.wide = cellsAcross(area.max().x() - area.min().x() + 1);
        this.deep = cellsAcross(area.max().z() - area.min().z() + 1);
        this.looked = new float[wide * deep];
        this.tries = new int[wide * deep];
        known.forEach(walked::markMask);
    }

    /** How well this cell is known — the better of what was walked and what a look ruled out. */
    private double confidence(int cell) {
        return Math.max(looked[cell], walked.confidence(cell));
    }

    /**
     * Credits a look, and banks the cell WHOLE if that carried it over the line. A partial look is
     * not banked: it is a fact about a viewpoint, not about the ground.
     */
    private void raise(int cell, float to) {
        if (to <= looked[cell] || confidence(cell) >= ENOUGH) {
            return;
        }
        looked[cell] = to;
        if (to >= ENOUGH) {
            settle(cell);
        }
    }

    /** Declares a whole cell known, and tells whoever is tracking this sweep. */
    private void settle(int cell) {
        looked[cell] = 1.0f;
        walked.markFull(walked.cornerOf(cell));
        coverage.settled(walked.cornerOf(cell));
    }

    /** The min corner of a cell, in world coordinates — the handle a caller names it by. */
    public Pos cornerOf(int cell) {
        return new Pos(area.min().x() + (cell / deep) * CELL, area.min().y(),
                area.min().z() + (cell % deep) * CELL);
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
        Pos lastLook = this.lookedFrom;
        return lastLook == null
                || horizontalDistance(lastLook, ctx.percepts().position()) >= CELL;
    }

    /**
     * The ground the body's near field is passing over — the OMNIDIRECTIONAL halo, not the wider
     * aperture the crescent sweeps, or a sweep declares ground known that nobody looked at and
     * leaves a tree standing in a box somebody was told was clear.
     *
     * <p><b>A body with no near field at all still learns by walking.</b>
     * {@code places.near_radius} of zero is legal (the test species does it) and such a body would
     * otherwise never finish a box, since it walks to cell centres and no square would ever be in
     * range. Only then is the cell underfoot taken whole — for anything with a near field the
     * geometry already covers it, and taking it whole would be the overclaim this model removes.
     */
    private void markWalked(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        int near = CrescentSampler.nearRadius(ctx.profile());
        if (near <= 0) {
            int underfoot = walked.cellAt(here.x(), here.z());
            if (underfoot >= 0) {
                settle(underfoot);
            }
            return;
        }
        if (walked.markNear(here, near)) {
            coverage.near(here, near);
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
                    // Through settle(), not a direct write: a write-off the sink never hears about
                    // stays on the frontier forever and its slice is re-offered without end.
                    settle(target);
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
            this.lookedFrom = here;
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
        this.lookedFrom = this.standing;
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
        BlockProbe probe = ctx.percepts().blocks();
        for (int cell = 0; cell < looked.length; cell++) {
            if (occupied.contains(cell)) {
                continue; // something is there; only walking near it will say what.
            }
            if (confidence(cell) >= 1.0) {
                continue;
            }
            double distance = horizontalDistance(from, centreOf(cell));
            // Measured to the cell's NEAREST corner, not its centre: a cell straddling the blind
            // ring is part unobservable. The live miss survived excluding the ring this way — a
            // tree nine blocks out in a cell whose centre was thirteen.
            if (distance > horizon || distance - CELL_REACH <= blind) {
                continue;
            }
            // An OBSTRUCTED view is worth less than distance alone suggests; a clear one is worth
            // no more. Crediting a clear line of sight outright made sweeps instant and blind —
            // only the near field can INDIVIDUATE what stands on ground, and the box that yielded
            // fifty trees came back with none, reporting "done".
            double credit = 1.0 - distance / horizon;
            if (!probe.sightClearBetween(from, groundOf(centreOf(cell), probe))) {
                credit *= OBSTRUCTED_VIEW;
            }
            raise(cell, (float) credit);
        }
        seen.clear();
    }

    /** Heads for the least-known cell, nearest first among equals. */
    private TaskStatus startNext(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        int worst = -1;
        double worstScore = Double.MAX_VALUE;
        for (int cell = 0; cell < looked.length; cell++) {
            if (confidence(cell) >= ENOUGH) {
                continue;
            }
            // Confidence first, distance only to break ties: a body should not cross the box for a
            // slightly emptier cell, and should not stay near home ignoring an unknown corner.
            double score = confidence(cell) * 1_000_000 + horizontalDistance(here, centreOf(cell));
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
        for (int cell = 0; cell < looked.length; cell++) {
            if (confidence(cell) < ENOUGH) {
                return false;
            }
        }
        return true;
    }

    /** How many cells are known well enough — the progress everything else reports. */
    public int cellsKnown() {
        int known = 0;
        for (int cell = 0; cell < looked.length; cell++) {
            if (confidence(cell) >= ENOUGH) {
                known++;
            }
        }
        return known;
    }

    public int cells() {
        return looked.length;
    }

    /**
     * How well the cell containing this position is known, 0..1 — what a debug view shades by.
     * Zero outside the box.
     */
    public double confidenceAt(Pos at) {
        int cell = cellAt(at);
        return cell < 0 ? 0.0 : confidence(cell);
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

    /**
     * The cell centre dropped onto whatever stands there — the line of sight has to be tested
     * against the GROUND, not against a point in the air at the surveyor's own altitude, or a
     * body on a hilltop reports a clear view of the sky above a valley it cannot see into.
     */
    private static Pos groundOf(Pos centre, BlockProbe probe) {
        return new Pos(centre.x(), probe.surfaceY(centre.x(), centre.z()) + 1, centre.z());
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
     *
     * <p>{@code looked} and {@code masks} are the two halves {@link #confidence(int)} maxes
     * together — a look's per-cell credit, and the walked grid's per-cell bitmask — carried
     * separately because they mean different things and only one of them unions.
     */
    public record State(Region area, PoiKind looking, List<Float> looked, List<Integer> masks,
                        List<Integer> tries, int target) {
    }

    /** What this sweep would need to carry on where it left off. */
    public State snapshot() {
        List<Float> lookedState = new ArrayList<>(looked.length);
        for (float value : looked) {
            lookedState.add(value);
        }
        List<Integer> masks = new ArrayList<>(cells());
        for (int cell = 0; cell < cells(); cell++) {
            masks.add(walked.mask(cell));
        }
        List<Integer> attempts = new ArrayList<>(tries.length);
        for (int value : tries) {
            attempts.add(value);
        }
        return new State(area, looking, lookedState, masks, attempts, target);
    }

    /** Puts the coverage back. A walk or a look in flight is re-decided on the next tick. */
    public SurveyArea restore(State state) {
        for (int cell = 0; cell < looked.length && cell < state.looked().size(); cell++) {
            looked[cell] = state.looked().get(cell);
        }
        for (int cell = 0; cell < cells() && cell < state.masks().size(); cell++) {
            walked.markMask(walked.cornerOf(cell), state.masks().get(cell));
        }
        for (int cell = 0; cell < tries.length && cell < state.tries().size(); cell++) {
            tries[cell] = state.tries().get(cell);
        }
        this.target = state.target();
        return this;
    }
}
