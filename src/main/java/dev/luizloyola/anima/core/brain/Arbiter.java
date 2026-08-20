package dev.luizloyola.anima.core.brain;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.log.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

/**
 * Every tick, reads each {@link Instinct}'s pressure, picks the winner, and keeps its one
 * {@link TaskExecutor} running the winner's task tree; publishes the executor's method-cost ceiling
 * ({@link #costTolerance}), which is the active drive's own. The arbiter alone grants
 * instinct-driven work, so {@code active} names the instinct whose root runs — {@code null} for
 * idle, or a task installed on {@link #executor()} directly.
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
    /** The last drive journalled, so a re-grant of the same drive does not spam the BRAIN log. */
    private Instinct lastGranted;
    /**
     * The last drive to fail and why, so a run of identical failures writes one line instead of
     * one per cooldown. Some drives fail as a matter of course — {@code seek_people} FAILs on
     * every tick where nobody eligible is perceived, which IS its pacing — and a settler alone in
     * a field was measured writing that line every ~101 ticks indefinitely (2026-08-19, in-world).
     *
     * <p>Deliberately NOT persisted, unlike {@link #lastGranted}: one repeated line after a reload
     * is honest, since the situation really is being met afresh, and it is not worth widening a
     * saved codec for.
     */
    private Instinct lastFailed;
    private String lastFailureReason = "";

    /**
     * Every drive sitting out a fail-cooldown, by {@link Instinct#key()}, with the ticks left;
     * eligible drives are absent. Saved and restored, or a reload forgives every cooldown.
     */
    public Map<String, Integer> cooldowns() {
        Map<String, Integer> waiting = new LinkedHashMap<>();
        for (int i = 0; i < instincts.size(); i++) {
            if (cooldowns[i] > 0) {
                waiting.put(instincts.get(i).key(), cooldowns[i]);
            }
        }
        return waiting;
    }

    /** Puts saved fail-cooldowns back. An unknown key is ignored, not fatal — the rest still apply. */
    public void restoreCooldowns(Map<String, Integer> waiting) {
        for (int i = 0; i < instincts.size(); i++) {
            Integer left = waiting.get(instincts.get(i).key());
            cooldowns[i] = left == null ? 0 : Math.max(0, left);
        }
    }

    /**
     * Which drive holds the wheel; {@code active} is null when nothing is granted. <b>Only ever
     * saved and restored alongside the executor's plan</b> — a grant with no running root is the
     * half-a-commitment bug, paid for three times over.
     */
    public record Grant(String active, boolean workRunning, String lastGranted) {
    }

    public Grant grant() {
        return new Grant(active == null ? "" : active.key(), workRunning,
                lastGranted == null ? "" : lastGranted.key());
    }

    /** Puts a saved grant back. An unknown drive clears the grant rather than failing the load. */
    public void restoreGrant(Grant grant, WorkItem held) {
        this.active = byKey(grant.active());
        this.lastGranted = byKey(grant.lastGranted());
        // Errand and flag together or neither: the flag alone took the server down on a null claim.
        this.claimedItem = held;
        this.workRunning = grant.workRunning() && held != null;
    }

    private Instinct byKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (Instinct instinct : instincts) {
            if (instinct.key().equals(key)) {
                return instinct;
            }
        }
        return null;
    }

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
            // The belief goes with the claim: the terminal report below trusts this flag ALONE, so
            // leaving it set reports against a null errand and kills the tick (live-caught).
            workRunning = false;
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
                // Only the first of a run, the way the grant path dedupes through lastGranted.
                // A CHANGED reason still speaks: the same drive failing a new way is news, and
                // collapsing that would hide the one failure somebody needs to see.
                if (active != lastFailed || !reason.equals(lastFailureReason)) {
                    ctx.journal().record(Category.BRAIN, active.describe(), "failed"
                            + (reason.isEmpty() ? "" : " — " + reason));
                    lastFailed = active;
                    lastFailureReason = reason;
                }
                cooldowns[indexOf(active)] = active.failCooldown();
                // Reported from here, not the driver: the cooldown and the terminal status are both
                // known only here. Dormant in every board today — see WorkSource#driveFailed.
                work.driveFailed(active, reason, ctx);
            } else if (active != null) {
                // It got somewhere. The run is over, so the next failure is a fresh story rather
                // than the same one repeating.
                lastFailed = null;
                lastFailureReason = "";
            }
            active = null; // next tick's idle-grant re-arbitrates
        }
    }

    /**
     * The cost ceiling for method selection — the active drive's own
     * ({@link Instinct#costTolerance}), {@link Double#POSITIVE_INFINITY} when nothing is active.
     *
     * <p>Read live rather than cached at the grant, because a need drive's budget is the level its
     * body is at right now: a body that goes from hungry to starving mid-errand may pay more for
     * the rest of it without waiting to be re-granted.
     */
    public double costTolerance(BrainContext ctx) {
        if (workRunning && claimedItem != null) {
            // Decoupled from the needs' desperation curve on purpose: a job is worth a fixed
            // effort, set by policy — see WorkToleranceCurve.
            return WorkToleranceCurve.tolerance(claimedItem.priority());
        }
        return active == null ? Double.POSITIVE_INFINITY : active.costTolerance(ctx);
    }

    /**
     * What is spoken for right now, best first: the held errand's kit ahead of any standing want,
     * because the pickaxe for the errand in hand outranks the axe a body merely likes to carry.
     *
     * <p>Read live rather than cached at the grant, for the reason {@link #costTolerance} is — a
     * body that claims a mining errand mid-stow must stop stowing its pickaxe on the next tick, not
     * on the next grant. {@link #claimedItem} survives a suspension, so a preempted errand keeps
     * its kit reserved while the body is away doing something else.
     */
    public List<ItemCall> reserved(BrainContext ctx) {
        List<ItemCall> spoken = new ArrayList<>();
        if (claimedItem != null) {
            spoken.addAll(claimedItem.kit().calls());
        }
        spoken.addAll(work.reserved(ctx));
        return List.copyOf(spoken);
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
        // Through the kit wrap: needs fetched, wants tried, then the item's own root — all
        // INSIDE the claim taken above, which is the invariant that stops two bodies shopping
        // for the same errand. A kit-less item comes back unwrapped.
        executor.run(dev.luizloyola.anima.core.brain.task.KittedErrand.around(item), ctx);
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
        executor.run(instinct.root(ctx), ctx); // run() cancels any incumbent first
    }

    private int indexOf(Instinct instinct) {
        return instinct == null ? -1 : instincts.indexOf(instinct);
    }
}
