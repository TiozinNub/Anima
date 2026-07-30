package dev.luizloyola.anima.core.brain;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Category;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every tick, reads each {@link Instinct}'s pressure, picks the winner, and keeps its one
 * {@link TaskExecutor} running the winner's task tree; publishes the executor's method-cost ceiling
 * ({@link #costTolerance()}) from the active drive's pressure through {@link ToleranceCurve}. The
 * arbiter alone grants instinct-driven work, so {@code active} names the instinct whose root runs —
 * {@code null} for idle, or a task installed on {@link #executor()} directly.
 *
 * <h2>Per-tick arbitration ({@link #tick})</h2>
 * <ol>
 *   <li>Pressures read once; a drive serving the cooldown {@link Instinct#failCooldown()} set after
 *       a FAILED root is INELIGIBLE this tick, then counts down.</li>
 *   <li>Top eligible bidder by EFFECTIVE pressure — incumbent plus
 *       {@link #stickiness(AgentProfile)}, ties to the earlier instinct in the constructor list.
 *       <b>Zero raw pressure is not a bid</b>: all-zero idles rather than granting by default
 *       (live-caught — zero-pressure Flee won that tie by list order and sprinted them out of the
 *       loaded world).</li>
 *   <li>Idle → grant the top bidder, {@code root()} called anew: re-granting the incumbent after
 *       SUCCESS is the continuous-behavior loop.</li>
 *   <li>Busy → switch only if the challenger beats the incumbent on effective pressure and its RAW
 *       pressure reaches {@link #preempt(AgentProfile)}; below that it waits for the task boundary,
 *       and switching cancels the incumbent's task.</li>
 *   <li>The executor ticks once; across a boundary a FAILED root goes on cooldown and
 *       {@code active} clears either way, re-arbitrating next tick.</li>
 * </ol>
 * With none eligible the executor still ticks — a manual task must keep running.
 */
public final class Arbiter {

    /** Incumbency bonus on the active instinct's bid — the hysteresis that stops 51/49 dithering. */
    public static double stickiness(AgentProfile profile) {
        return profile.d(ProfileAspect.MIND_STICKINESS);
    }

    /** Minimum RAW pressure to preempt mid-flight; below it a challenger waits for the boundary. */
    public static double preempt(AgentProfile profile) {
        return profile.d(ProfileAspect.MIND_PREEMPT);
    }

    private final List<Instinct> instincts;
    private final WorkSource work;
    private final TaskExecutor executor = new TaskExecutor();

    /** Per-instinct cooldown counters (parallel to {@link #instincts}); {@code 0} means eligible. */
    private final int[] cooldowns;
    /** Last tick's pressures, cached for the ctx-less {@link #describe()}. */
    private final double[] lastPressures;

    /** The instinct whose root is currently running, or {@code null} (idle, or a manual task). */
    private Instinct active;
    /** Currently OWED — kept through suspensions: a preempt cancels the errand's tree, not the claim. */
    private WorkItem claimedItem;
    /** Whether the executor's current root belongs to {@link #claimedItem} (vs a drive's). */
    private boolean workRunning;
    /** The active instinct's pressure as of the last arbitration — the source for {@link #costTolerance()}. */
    private double activePressure;
    /** The last drive journalled, so a re-grant of the same drive does not spam the BRAIN log. */
    private Instinct lastGranted;

    /** An arbiter with no work source — drives only (tests, minimal rigs). */
    public Arbiter(List<Instinct> instincts) {
        this(instincts, WorkSource.NONE);
    }

    public Arbiter(List<Instinct> instincts, WorkSource work) {
        this.instincts = List.copyOf(instincts);
        this.work = work;
        this.cooldowns = new int[this.instincts.size()];
        this.lastPressures = new double[this.instincts.size()];
    }

    /** One arbitration + execution step — see the class doc for the exact semantics. */
    public void tick(BrainContext ctx) {
        int n = instincts.size();
        // One reading of the arbitration constants for the whole tick: a reload landing between
        // the bid comparison and the preempt check would otherwise arbitrate against two
        // different rulebooks in a single decision.
        double stickiness = stickiness(ctx.profile());
        double preempt = preempt(ctx.profile());

        // 1. Eligibility is noted before the countdown, so a fresh cooldown buys that many ticks.
        boolean[] eligible = new boolean[n];
        for (int i = 0; i < n; i++) {
            eligible[i] = cooldowns[i] == 0;
            if (cooldowns[i] > 0) {
                cooldowns[i]--;
            }
            lastPressures[i] = instincts.get(i).pressure(ctx);
        }

        // 2. Top eligible bidder by effective pressure (incumbent gets STICKINESS; ties -> earlier).
        int activeIndex = indexOf(active);
        int topIndex = -1;
        double topEffective = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (!eligible[i] || lastPressures[i] <= 0.0) {
                continue; // cooling down, or wanting nothing — zero pressure is not a bid
            }
            double effective = lastPressures[i] + (i == activeIndex ? stickiness : 0.0);
            if (effective > topEffective) { // strict > keeps the earlier entry on a tie
                topEffective = effective;
                topIndex = i;
            }
        }

        // 2a. An OWED commitment nothing is running has not been heartbeated: check it is still
        //     theirs before re-bidding, or two agents end up on one errand. Gated on the EXECUTOR
        //     being idle, not on the stale-able workRunning — a manual dev order takes the wheel
        //     without the arbiter ticking, so the check would never fire (live-caught).
        if (claimedItem != null && !executor.isBusy() && !work.stillMine(claimedItem, ctx)) {
            ctx.journal().record(Category.PROJECT, claimedItem.describe(),
                    "dropped — the claim lapsed while they were away");
            claimedItem = null;
        }

        // 2b. The commitment bid: the item already owed, else the board's best offer — one more
        //     bidder on the same 0..1 scale (fixed board priority, not body pressure).
        WorkItem candidate = claimedItem != null
                ? claimedItem
                : work.bestAvailable(ctx).orElse(null);
        double workEffective = candidate == null
                ? Double.NEGATIVE_INFINITY
                : candidate.priority() + (workRunning ? stickiness : 0.0);

        // 3 & 4. Work never preempts mid-flight; a drive cuts a running errand only past the
        // PREEMPT bar, and the claim survives the cut.
        if (!executor.isBusy()) {
            if (candidate != null && workEffective > topEffective) {
                grantWork(candidate, ctx);
            } else if (topIndex >= 0) {
                grant(topIndex, ctx);
            }
        } else if (workRunning) {
            if (topIndex >= 0 && lastPressures[topIndex] >= preempt && topEffective > workEffective) {
                ctx.journal().record(Category.PROJECT, claimedItem.describe(), String.format(Locale.ROOT,
                        "suspended (by %s %.2f)",
                        instincts.get(topIndex).describe(), lastPressures[topIndex]));
                workRunning = false;
                grant(topIndex, ctx); // run() cancels the errand's tree; the claim is KEPT
            }
        } else if (topIndex >= 0 && topIndex != activeIndex) {
            double activeEffective = activeIndex >= 0
                    ? lastPressures[activeIndex] + stickiness
                    : Double.NEGATIVE_INFINITY; // a manual task (no active instinct) yields to any real bidder... but only if it preempts
            if (topEffective > activeEffective && lastPressures[topIndex] >= preempt) {
                grant(topIndex, ctx);
            }
        }

        // Keep the tolerance source current for an incumbent that kept running (wasn't re-granted).
        if (active != null) {
            activePressure = lastPressures[indexOf(active)];
        }

        // 4b. The heartbeat is the only thing keeping the hold alive — stop long enough (a
        //     suspension, a death) and the board takes the errand back.
        if (workRunning && claimedItem != null) {
            work.heartbeat(claimedItem, ctx);
        }

        // 5. Run one step; detect a task boundary crossed this tick and react to its outcome.
        boolean busyBefore = executor.isBusy();
        executor.tick(ctx);
        if (busyBefore && !executor.isBusy()) {
            if (workRunning) {
                // The errand reached a terminal: report to the board either way. Failure pacing
                // is the ITEM's (board cooldown), never an instinct cooldown.
                if (executor.lastStatus().orElse(null) == TaskStatus.FAILED) {
                    ctx.journal().record(Category.PROJECT, claimedItem.describe(), "failed"
                            + executor.failureReason().map(r -> " — " + r).orElse(""));
                    work.failed(claimedItem, ctx);
                } else {
                    ctx.journal().record(Category.PROJECT, claimedItem.describe(),
                            "completed (" + claimedItem.progress(ctx) + ")");
                    work.completed(claimedItem, ctx);
                }
                claimedItem = null;
                workRunning = false;
            } else if (active != null && executor.lastStatus().orElse(null) == TaskStatus.FAILED) {
                // BRAIN log: failures only — every wander SUCCESS would be noise, and the
                // take-over lines already mark what they started.
                String reason = executor.failureReason().orElse("");
                ctx.journal().record(Category.BRAIN, active.describe(), "failed"
                        + (reason.isEmpty() ? "" : " — " + reason));
                cooldowns[indexOf(active)] = active.failCooldown();
                // Reported from here, not the driver: the cooldown and the terminal status are both
                // known only here. Dormant in every board today — see WorkSource#driveFailed.
                work.driveFailed(active, reason, ctx);
            }
            active = null; // next tick's idle-grant re-arbitrates
        }
    }

    /**
     * The cost ceiling for method selection: {@link ToleranceCurve} of the active drive's pressure,
     * {@link Double#POSITIVE_INFINITY} when nothing is active.
     */
    public double costTolerance() {
        if (workRunning && claimedItem != null) {
            // Decoupled from the needs' desperation curve on purpose: a job is worth a fixed
            // effort, set by policy — see WorkToleranceCurve.
            return WorkToleranceCurve.tolerance(claimedItem.priority());
        }
        return active == null ? Double.POSITIVE_INFINITY : ToleranceCurve.tolerance(activePressure);
    }

    /** The executor the arbiter drives — the mod driver's manual-mode entry point and status source. */
    public TaskExecutor executor() {
        return executor;
    }

    /**
     * The instinct whose root is running, or empty — idle, or a claimed errand. Identity, not a
     * name: a caller that HANDED an instinct in can ask whether that exact drive has the wheel.
     *
     * <p>Only as current as the last {@link #tick} — a task installed straight on the
     * {@link #executor()} bypasses arbitration, so this keeps naming the drive granted before it.
     */
    public Optional<Instinct> activeDrive() {
        return Optional.ofNullable(active);
    }

    /**
     * One line per instinct — name, pressure to 2dp, {@code (active)} or {@code (cooldown Nt)} —
     * then the executor's describe. Ctx-less, printable any time.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (String line : pressureLines()) {
            sb.append(line).append('\n');
        }
        sb.append(executor.describe());
        return sb.toString();
    }

    /**
     * Per-instinct pressure with its tag, and the claimed work item — one line each, without the
     * executor's task chain (see {@link TaskExecutor#describeLines}), so a stacked readout composes
     * the halves without re-parsing a joined string.
     */
    public List<String> pressureLines() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < instincts.size(); i++) {
            Instinct instinct = instincts.get(i);
            StringBuilder sb = new StringBuilder(instinct.describe())
                    .append(String.format(Locale.ROOT, " %.2f", lastPressures[i]));
            if (instinct == active) {
                sb.append(" (active)");
            } else if (cooldowns[i] > 0) {
                sb.append(" (cooldown ").append(cooldowns[i]).append("t)");
            }
            lines.add(sb.toString());
        }
        if (claimedItem != null) {
            lines.add("work: " + claimedItem.describe()
                    + (workRunning ? " (active)" : " (suspended)"));
        }
        return lines;
    }

    // --- internals -------------------------------------------------------------------------------

    /** Engage (claim) or resume the work item: a fresh root either way — resume re-decomposes
     *  against the changed world, and achieve-goals count prior progress automatically. */
    private void grantWork(WorkItem item, BrainContext ctx) {
        if (claimedItem != item) {
            claimedItem = item;
            work.claimed(item, ctx);
            ctx.journal().record(Category.PROJECT, item.describe(), String.format(Locale.ROOT,
                    "claimed (priority %.2f)", item.priority()));
        } else {
            ctx.journal().record(Category.PROJECT, item.describe(),
                    "resumed (" + item.progress(ctx) + ")");
        }
        active = null;
        workRunning = true;
        executor.run(item.root(), ctx);
    }

    /** Install instinct {@code i}'s fresh root as the running task, recording it as active. */
    private void grant(int i, BrainContext ctx) {
        Instinct instinct = instincts.get(i);
        // BRAIN log: only a genuine change of drive, not the incumbent re-granting itself after
        // each SUCCESS — an idle Person's wander re-rolls would swamp the ring.
        if (instinct != lastGranted) {
            boolean preempt = executor.isBusy() && active != null && active != instinct;
            ctx.journal().record(Category.BRAIN, instinct.describe(), String.format(Locale.ROOT,
                    "%s (pressure %.2f)", preempt ? "preempt" : "take over", lastPressures[i]));
            lastGranted = instinct;
        }
        active = instinct;
        activePressure = lastPressures[i];
        executor.run(instinct.root(ctx), ctx); // run() cancels any incumbent first
    }

    private int indexOf(Instinct instinct) {
        return instinct == null ? -1 : instincts.indexOf(instinct);
    }
}
