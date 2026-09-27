package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ConsumeState;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.instinct.Drives;
import dev.luizloyola.anima.core.brain.instinct.FleeInstinct;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import dev.luizloyola.anima.core.brain.instinct.WanderInstinct;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.agent.FoodValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link Arbiter}'s arbitration semantics — idle grant, {@link Arbiter#stickiness()},
 * the {@link Arbiter#preempt()} floor, fresh-root re-grant, {@link Instinct#failCooldown()},
 * manual tasks, cost tolerance, {@link Arbiter#activeDrive()}, the mute — against scripted
 * instincts, plus two scenes wired from the real ones.
 */
class ArbiterTest {

    private final FakeContext ctx = new FakeContext();

    // --- scripted pieces -------------------------------------------------------------------------

    /** A primitive that reports RUNNING for {@code runFor} ticks then {@code end}; counts ticks and cancels. */
    private static final class Step implements PrimitiveTask {
        final String name;
        private int runFor;
        private final TaskStatus end;
        int ticks;
        int cancels;

        private final String reason;

        Step(String name, int runFor, TaskStatus end) {
            this(name, runFor, end, null);
        }

        /** With a failure reason, for the tests about what the journal collapses. */
        Step(String name, int runFor, TaskStatus end, String reason) {
            this.name = name;
            this.runFor = runFor;
            this.end = end;
            this.reason = reason;
        }

        @Override
        public String failureDetail() {
            return reason == null ? PrimitiveTask.super.failureDetail() : reason;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            ticks++;
            return runFor-- > 0 ? TaskStatus.RUNNING : end;
        }

        @Override
        public void cancel(BrainContext ctx) {
            cancels++;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /**
     * An instinct with settable pressure and a root FACTORY — every grant records a fresh root.
     * {@link #failCooldownOverride} pins an emergency drive's shortened cooldown per test (see
     * {@link FleeInstinct#FAIL_COOLDOWN}).
     */
    private static final class FakeInstinct implements Instinct {
        final String name;
        double pressure;
        private final Supplier<Task> factory;
        final List<Task> grantedRoots = new ArrayList<>();
        int failCooldownOverride = Instinct.DEFAULT_FAIL_COOLDOWN;
        double budget = Double.POSITIVE_INFINITY;
        boolean yields;
        Deed deed = Deed.of(FakeDoings.DID_IT);

        FakeInstinct(String name, double pressure, Supplier<Task> factory) {
            this.name = name;
            this.pressure = pressure;
            this.factory = factory;
        }

        @Override
        public double pressure(BrainContext ctx) {
            return pressure;
        }

        @Override
        public Task root(BrainContext ctx) {
            Task t = factory.get();
            grantedRoots.add(t);
            return t;
        }

        @Override
        public int failCooldown() {
            return failCooldownOverride;
        }

        @Override
        public double costTolerance(BrainContext ctx) {
            return budget;
        }

        @Override
        public boolean yields(BrainContext ctx) {
            return yields;
        }

        @Override
        public Deed doing(BrainContext ctx) {
            return deed;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /**
     * A real {@link FleeInstinct} with grant recording spliced on: instance freshness
     * ({@link #grantedRoots}) cannot be observed from outside the arbiter/executor otherwise.
     */
    private static final class SpyingFlee implements Instinct {
        private final FleeInstinct real;
        final List<Task> grantedRoots = new ArrayList<>();

        SpyingFlee(RandomGenerator random) {
            this.real = new FleeInstinct();
        }

        @Override
        public double pressure(BrainContext ctx) {
            return real.pressure(ctx);
        }

        @Override
        public Task root(BrainContext ctx) {
            Task t = real.root(ctx);
            grantedRoots.add(t);
            return t;
        }

        @Override
        public int failCooldown() {
            return real.failCooldown();
        }

        @Override
        public Deed doing(BrainContext ctx) {
            return real.doing(ctx);
        }

        @Override
        public String describe() {
            return real.describe();
        }
    }

    private static Supplier<Task> forever(String name) {
        return () -> new Step(name, Integer.MAX_VALUE, TaskStatus.SUCCESS);
    }

    private static Supplier<Task> failsImmediately(String name) {
        return () -> new Step(name, 0, TaskStatus.FAILED);
    }

    private static Supplier<Task> succeedsImmediately(String name) {
        return () -> new Step(name, 0, TaskStatus.SUCCESS);
    }

    private static Step step(Task root) {
        return (Step) root;
    }

    // --- history ---------------------------------------------------------------------------------

    private static final Deed FLED_ZOMBIE = Deed.of(Doings.FLEEING, Slot.entity("zombie"));
    private static final Deed FLED_SPIDER = Deed.of(Doings.FLEEING, Slot.entity("spider"));

    @Test
    void aDriveThatSucceedsIsRememberedAsItWasGranted() {
        FakeInstinct flee = new FakeInstinct("flee", 0.5, () -> new Step("run", 2, TaskStatus.SUCCESS));
        flee.deed = FLED_ZOMBIE;
        Arbiter arbiter = new Arbiter(List.of(flee));

        arbiter.tick(ctx); // granted: the zombie is what it was fleeing
        flee.deed = FLED_SPIDER; // by the time it got away, something else was in view
        arbiter.tick(ctx);
        arbiter.tick(ctx);

        assertEquals(List.of(FLED_ZOMBIE),
                arbiter.history().recent(0).stream().map(History.Entry::deed).toList(),
                "the deed is captured at the grant: a fled zombie is out of sight by the end");
    }

    @Test
    void aDriveThatFailsIsNotRemembered() {
        FakeInstinct eat = new FakeInstinct("eat", 0.5, failsImmediately("eat"));
        eat.deed = Deed.of(Doings.EATING);
        Arbiter arbiter = new Arbiter(List.of(eat));
        arbiter.tick(ctx);

        assertTrue(arbiter.history().recent(0).isEmpty(), "a failed meal is not \"had a bite\"");
    }

    @Test
    void aPreemptedDriveIsNotRemembered() {
        FakeInstinct eat = new FakeInstinct("eat", 0.3, forever("eat"));
        eat.deed = Deed.of(Doings.EATING);
        FakeInstinct flee = new FakeInstinct("flee", 0.0, succeedsImmediately("run"));
        flee.deed = FLED_ZOMBIE;
        Arbiter arbiter = new Arbiter(List.of(flee, eat));
        arbiter.tick(ctx);
        flee.pressure = 0.9;
        arbiter.tick(ctx);

        assertEquals(List.of(FLED_ZOMBIE),
                arbiter.history().recent(0).stream().map(History.Entry::deed).toList(),
                "the meal was cut off, not finished");
    }

    // --- idle grant ------------------------------------------------------------------------------

    @Test
    void idleGrantsTheTopBidder() {
        FakeInstinct a = new FakeInstinct("a", 0.5, forever("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.3, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));
        arbiter.tick(ctx);
        assertEquals(1, a.grantedRoots.size(), "the higher bidder is granted");
        assertEquals(1, step(a.grantedRoots.get(0)).ticks, "and immediately driven");
        assertEquals(0, b.grantedRoots.size(), "the loser is not");
    }

    @Test
    void tiesGoToTheEarlierInstinct() {
        FakeInstinct a = new FakeInstinct("a", 0.4, forever("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.4, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));
        arbiter.tick(ctx);
        assertEquals(1, a.grantedRoots.size(), "equal bids -> the earlier list entry wins");
        assertEquals(0, b.grantedRoots.size());
    }

    @Test
    void zeroPressureIsNotABid() {
        FakeInstinct flee = new FakeInstinct("flee", 0.0, forever("scatter"));
        FakeInstinct wander = new FakeInstinct("wander", 0.0, forever("roam"));
        Arbiter arbiter = new Arbiter(List.of(flee, wander));
        for (int i = 0; i < 20; i++) {
            arbiter.tick(ctx);
        }
        assertTrue(flee.grantedRoots.isEmpty(), "an all-zero field grants nobody by list order");
        assertTrue(wander.grantedRoots.isEmpty());
        assertFalse(arbiter.executor().isBusy(), "wanting nothing means idling, not busywork");
    }

    @Test
    void aCoolingWanderLeavesThemStandingNotScatterFleeing() {
        // Live-caught: wander fails, cools down, and zero-pressure flee won the all-zero tie by
        // list order — a sprint at nothing, clean out of the loaded world.
        FakeInstinct flee = new FakeInstinct("flee", 0.0, forever("scatter"));
        FakeInstinct wander = new FakeInstinct("wander", 0.15,
                () -> new Step("roam", 1, TaskStatus.FAILED));
        Arbiter arbiter = new Arbiter(List.of(flee, wander));
        for (int i = 0; i < 40; i++) {
            arbiter.tick(ctx); // grant, fail, and then the whole cooldown stretch
        }
        assertTrue(flee.grantedRoots.isEmpty(),
                "flee at 0.00 never inherits the wheel — they stand out the cooldown");
    }

    // --- what the take-over beat -----------------------------------------------------------------

    /**
     * The winner alone never explained itself; the line carries the bid it had to clear — the
     * best of the losers, which the list order deliberately does not agree with here.
     */
    @Test
    void aTakeOverNamesTheDriveItBeat() {
        List<Entry> lines = new ArrayList<>();
        ctx.journalService.subscribe((who, entry) -> lines.add(entry));
        FakeInstinct eat = new FakeInstinct("eat", 0.9, forever("sat"));
        FakeInstinct sleep = new FakeInstinct("sleep", 0.05, forever("doze"));
        FakeInstinct wander = new FakeInstinct("wander", 0.15, forever("roam"));
        new Arbiter(List.of(eat, sleep, wander)).tick(ctx);

        assertEquals("take over (0.90, beat wander 0.15)", takeOvers(lines));
    }

    /** Nobody else bid, so the clause is dropped rather than printed hollow. */
    @Test
    void aSoleBidderIsNotSaidToHaveBeatenAnything() {
        List<Entry> lines = new ArrayList<>();
        ctx.journalService.subscribe((who, entry) -> lines.add(entry));
        FakeInstinct wander = new FakeInstinct("wander", 0.15, forever("roam"));
        FakeInstinct flee = new FakeInstinct("flee", 0.0, forever("scatter"));
        new Arbiter(List.of(wander, flee)).tick(ctx);

        assertEquals("take over (0.15)", takeOvers(lines),
                "a silent drive lost nothing — zero pressure was never a bid");
    }

    /** Every BRAIN take-over line of a run, joined: a missing one then fails readably. */
    private static String takeOvers(List<Entry> lines) {
        return lines.stream().map(Entry::detail).filter(d -> d.startsWith("take over"))
                .collect(Collectors.joining("; "));
    }

    // --- stickiness ------------------------------------------------------------------------------

    @Test
    void stickinessHoldsAgainstAMarginalChallengerAndYieldsToADecisiveOne() {
        FakeInstinct a = new FakeInstinct("a", 0.5, forever("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));
        arbiter.tick(ctx); // A granted (0.5 > 0)
        assertEquals(1, a.grantedRoots.size());

        b.pressure = 0.55; // above A's raw 0.5, below A's effective 0.6
        arbiter.tick(ctx);
        assertEquals(0, b.grantedRoots.size(), "0.55 < 0.5 + STICKINESS 0.1 -> the incumbent holds");
        assertEquals(0, step(a.grantedRoots.get(0)).cancels);

        b.pressure = 0.61; // now above A's effective 0.6 and at/over PREEMPT 0.6
        arbiter.tick(ctx);
        assertEquals(1, b.grantedRoots.size(), "0.61 beats the sticky incumbent -> yields");
        assertEquals(1, step(a.grantedRoots.get(0)).cancels, "the incumbent's task was cancelled");
    }

    // --- preempt floor ---------------------------------------------------------------------------

    @Test
    void subPreemptChallengerWaitsWhileBusyThenWinsAtTheBoundary() {
        // A gets granted while its pressure is high, then drops below the challenger — but the
        // challenger is under PREEMPT, so it cannot cut in until A's task finishes on its own.
        FakeInstinct a = new FakeInstinct("a", 0.9, () -> new Step("aRoot", 2, TaskStatus.SUCCESS));
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: grant A (runFor 2 -> RUNNING); A ticks once
        a.pressure = 0.2;
        b.pressure = 0.45; // higher than A's 0.2, but < PREEMPT 0.6

        arbiter.tick(ctx); // t2: A's 2nd (last) RUNNING; B waits — sub-PREEMPT can't cut in
        assertEquals(0, b.grantedRoots.size(), "a sub-PREEMPT challenger never cuts in mid-task");

        arbiter.tick(ctx); // t3: A returns SUCCESS -> boundary; active clears (B still not granted)
        assertEquals(0, b.grantedRoots.size(), "not granted on the boundary tick itself");
        assertEquals(0, step(a.grantedRoots.get(0)).cancels, "A finished on its own terms — never cancelled");

        arbiter.tick(ctx); // t4: idle -> B (0.45) is now the top bidder -> granted
        assertEquals(1, b.grantedRoots.size(), "the challenger wins at the next boundary");
        assertEquals(1, step(b.grantedRoots.get(0)).ticks);
    }

    @Test
    void preemptChallengerCancelsTheRunningTaskMidFlight() {
        // A holds the legs (a real GoTo); a challenger at/over PREEMPT cancels it immediately.
        FakeInstinct a = new FakeInstinct("a", 0.9, () -> new GoTo(1, 2, 3));
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: grant A; the GoTo issues its move
        assertEquals(1, ctx.mover.moveToCalls);

        a.pressure = 0.3;
        b.pressure = 0.7; // > A's effective 0.4 and >= PREEMPT 0.6
        arbiter.tick(ctx); // t2: B preempts -> GoTo cancelled (mover stopped) before B acts
        assertEquals(1, ctx.mover.stopCalls, "the preempted GoTo released the legs");
        assertEquals(1, b.grantedRoots.size());
        assertEquals(List.of("moveTo(1, 2, 3)", "stop"), ctx.mover.events, "released, then the newcomer takes over");
    }

    @Test
    void aYieldingIncumbentIsCutIntoBelowThePreemptBar() {
        // Idling is a stroll and a pause, nothing to finish: a real drive under PREEMPT takes the
        // wheel at once instead of waiting out the pause. A hailed body that turned round nine
        // seconds later read as ignoring the player who clicked it.
        FakeInstinct idle = new FakeInstinct("idle", 0.15, forever("idleRoot"));
        idle.yields = true;
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(idle, b));

        arbiter.tick(ctx); // t1: grant idle
        b.pressure = 0.45; // beats idle's effective bid, still under PREEMPT 0.6
        arbiter.tick(ctx); // t2: cuts in anyway
        assertEquals(1, b.grantedRoots.size(), "a yielding incumbent makes nobody wait");
        assertEquals(1, step(idle.grantedRoots.get(0)).cancels, "the idle root was cancelled on the way out");
    }

    // --- the tap on the shoulder: the tick asked as a question ----------------------------------

    @Test
    void yieldsToAnswersWhatTheTickWouldDoWithoutGrantingAnything() {
        FakeInstinct a = new FakeInstinct("a", 0.9, forever("aRoot"));
        FakeInstinct idle = new FakeInstinct("idle", 0.15, forever("idleRoot"));
        idle.yields = true;

        Arbiter fresh = new Arbiter(List.of(a));
        assertTrue(fresh.yieldsTo(0.55, ctx), "idle: anything real would be granted");
        assertFalse(fresh.yieldsTo(0.0, ctx), "zero pressure is not a bid");
        assertEquals(0, a.grantedRoots.size(), "asking granted nothing");

        Arbiter busy = new Arbiter(List.of(a));
        busy.tick(ctx); // A holds the wheel and does not yield
        a.pressure = 0.3;
        busy.tick(ctx); // its bid has dropped, but a challenger still has to reach the bar
        assertFalse(busy.yieldsTo(0.55, ctx), "under the bar a busy body says no");
        assertTrue(busy.yieldsTo(0.7, ctx), "past the bar it would be cut into");
        assertEquals(1, a.grantedRoots.size(), "asking granted nothing more");

        Arbiter idling = new Arbiter(List.of(idle));
        idling.tick(ctx);
        assertTrue(idling.yieldsTo(0.55, ctx), "an idling body is willing");
        assertEquals(1, idle.grantedRoots.size(), "and was not interrupted by the question");
    }

    // --- boundary re-grant -----------------------------------------------------------------------

    @Test
    void successReGrantsTheSameInstinctWithAFreshRoot() {
        FakeInstinct a = new FakeInstinct("a", 0.5, succeedsImmediately("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: grant A; A succeeds immediately -> boundary
        arbiter.tick(ctx); // t2: idle -> A is still top -> re-granted with a new root
        assertEquals(2, a.grantedRoots.size(), "the same instinct is re-granted (the behavior loop)");
        assertNotSame(a.grantedRoots.get(0), a.grantedRoots.get(1), "each grant builds a fresh root");
        assertEquals(0, b.grantedRoots.size(), "the runner-up never ran");
    }

    // --- fail cooldown ---------------------------------------------------------------------------

    /**
     * A drive that keeps failing writes ONE line, not one per cooldown.
     *
     * <p>Measured in-world 2026-08-19: a settler whose only companion was suppressed by the hail
     * guardrail FAILED {@code seek_people} every ~101 ticks for the guardrail's whole window, and
     * every one of them landed in the journal. Some drives fail as a matter of course — that is
     * the pacing mechanism, not an incident — and the repeats crowd out the lines a reader is
     * actually looking for.
     */
    @Test
    void aDriveThatKeepsFailingTheSameWayIsRecordedOnce() {
        List<Entry> lines = new ArrayList<>();
        ctx.journalService.subscribe((who, entry) -> lines.add(entry));
        FakeInstinct a = new FakeInstinct("a", 1.0, failsImmediately("aRoot"));
        Arbiter arbiter = new Arbiter(List.of(a));

        for (int t = 0; t < 3 * (Instinct.DEFAULT_FAIL_COOLDOWN + 1); t++) {
            arbiter.tick(ctx);
        }

        assertTrue(a.grantedRoots.size() >= 3, "it really did fail repeatedly: " + a.grantedRoots.size());
        assertEquals(1, lines.stream().filter(e -> e.detail().startsWith("failed")).count(),
                "three failures, one line: " + lines);
    }

    /** A DIFFERENT reason is news, so it is not swallowed with the repeats. */
    @Test
    void aFailureThatChangesItsReasonSpeaksAgain() {
        List<Entry> lines = new ArrayList<>();
        ctx.journalService.subscribe((who, entry) -> lines.add(entry));
        List<String> reasons = List.of("no target", "no target", "path blocked");
        int[] next = {0};
        FakeInstinct a = new FakeInstinct("a", 1.0, () -> new Step("aRoot", 0, TaskStatus.FAILED,
                reasons.get(Math.min(next[0]++, reasons.size() - 1))));
        Arbiter arbiter = new Arbiter(List.of(a));

        for (int t = 0; t < 3 * (Instinct.DEFAULT_FAIL_COOLDOWN + 1); t++) {
            arbiter.tick(ctx);
        }

        List<String> failures = lines.stream().map(Entry::detail)
                .filter(d -> d.startsWith("failed")).toList();
        assertEquals(2, failures.size(), "the repeat collapses, the new reason does not: " + failures);
        assertTrue(failures.get(1).contains("path blocked"), failures.toString());
    }

    /** Getting somewhere ends the run, so the next failure is a fresh story rather than a repeat. */
    @Test
    void aSuccessInBetweenLetsTheNextFailureSpeak() {
        List<Entry> lines = new ArrayList<>();
        ctx.journalService.subscribe((who, entry) -> lines.add(entry));
        // A SUCCESS costs no cooldown, so the arbiter re-grants at once: the tail has to repeat
        // rather than run out, and the repeats past the third then collapse into one line.
        List<TaskStatus> outcomes = List.of(TaskStatus.FAILED, TaskStatus.SUCCESS, TaskStatus.FAILED);
        int[] next = {0};
        FakeInstinct a = new FakeInstinct("a", 1.0, () -> new Step("aRoot", 0,
                outcomes.get(Math.min(next[0]++, outcomes.size() - 1))));
        Arbiter arbiter = new Arbiter(List.of(a));

        for (int t = 0; t < 3 * (Instinct.DEFAULT_FAIL_COOLDOWN + 1); t++) {
            arbiter.tick(ctx);
        }

        assertEquals(2, lines.stream().filter(e -> e.detail().startsWith("failed")).count(),
                "a success between them means the second failure is not a repeat: " + lines);
    }


    @Test
    void failedRootPutsTheInstinctOnCooldownForExactlyItsOwnFailCooldownTicks() {
        FakeInstinct a = new FakeInstinct("a", 1.0, failsImmediately("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.5, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: A granted, fails -> cooldown 100 (the DEFAULT), active cleared
        assertEquals(1, a.grantedRoots.size());

        // t2..t101 (exactly DEFAULT_FAIL_COOLDOWN ticks): A sits out; B takes over and keeps running.
        for (int t = 2; t <= 1 + Instinct.DEFAULT_FAIL_COOLDOWN; t++) {
            arbiter.tick(ctx);
            assertEquals(1, a.grantedRoots.size(), "A still cooling at tick " + t);
        }
        assertEquals(1, b.grantedRoots.size(), "the runner-up took over while A cooled");
        Step running = step(b.grantedRoots.get(0));
        assertEquals(0, running.cancels, "B ran undisturbed through A's cooldown");

        arbiter.tick(ctx); // t102: A eligible again -> its 1.0 preempts B
        assertEquals(2, a.grantedRoots.size(), "A re-bids the tick AFTER exactly DEFAULT_FAIL_COOLDOWN ticks");
        assertEquals(1, running.cancels, "and preempts the runner-up");
    }

    /**
     * The emergency-drive shape (e.g. {@link FleeInstinct#FAIL_COOLDOWN}): the arbiter reads
     * {@code active.failCooldown()}, never a fixed constant of its own.
     */
    @Test
    void anInstinctOverridingFailCooldownSitsOutOnlyItsOwnShorterCooldown() {
        FakeInstinct a = new FakeInstinct("a", 1.0, failsImmediately("aRoot"));
        a.failCooldownOverride = 10;
        FakeInstinct b = new FakeInstinct("b", 0.5, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: A granted, fails -> cooldown 10 (its own override), active cleared
        assertEquals(1, a.grantedRoots.size());

        // t2..t11 (exactly its own 10-tick cooldown): A sits out; B takes over.
        for (int t = 2; t <= 1 + a.failCooldownOverride; t++) {
            arbiter.tick(ctx);
            assertEquals(1, a.grantedRoots.size(), "A still cooling at tick " + t);
        }
        assertEquals(1, b.grantedRoots.size(), "the runner-up took over while A cooled");

        arbiter.tick(ctx); // t12: A eligible again -> back bidding after exactly its own failCooldown
        assertEquals(2, a.grantedRoots.size(),
                "A re-bids after exactly its own failCooldown (10), far short of the 100 default");
    }

    @Test
    void aDriveCutOffByAPreemptSitsOutItsFailCooldown() {
        FakeInstinct a = new FakeInstinct("a", 0.7, forever("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.0, succeedsImmediately("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: A granted
        b.pressure = 0.9;
        arbiter.tick(ctx); // t2: B preempts A, runs its one tick and finishes
        assertEquals(1, step(a.grantedRoots.get(0)).cancels);
        b.pressure = 0.0;

        // t3..t102: A is the only bidder and still sits out, as if its root had failed.
        for (int t = 3; t <= 2 + Instinct.DEFAULT_FAIL_COOLDOWN; t++) {
            arbiter.tick(ctx);
            assertEquals(1, a.grantedRoots.size(), "A still cooling at tick " + t);
        }
        arbiter.tick(ctx); // t103
        assertEquals(2, a.grantedRoots.size(), "A re-bids once the cooldown is served");
    }

    @Test
    void aYieldingDriveCutIntoPaysNoCooldownEvenPastThePreemptBar() {
        FakeInstinct idle = new FakeInstinct("idle", 0.15, forever("idleRoot"));
        idle.yields = true;
        FakeInstinct b = new FakeInstinct("b", 0.0, succeedsImmediately("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(idle, b));

        arbiter.tick(ctx); // t1: grant idle
        b.pressure = 0.9;
        arbiter.tick(ctx); // t2: B cuts in and finishes
        b.pressure = 0.0;
        assertTrue(arbiter.cooldowns().isEmpty(), "idling had nothing to finish");

        arbiter.tick(ctx); // t3
        assertEquals(2, idle.grantedRoots.size(), "idle picks up again at once");
    }

    // --- manual task under an all-cooling / empty arbiter ----------------------------------------

    @Test
    void aManualTaskRunsUnderAnArbiterWithNoInstincts() {
        Arbiter arbiter = new Arbiter(List.of());
        Step manual = new Step("manual", Integer.MAX_VALUE, TaskStatus.SUCCESS);
        arbiter.executor().run(manual, ctx); // the driver's manual mode installs directly
        arbiter.tick(ctx);
        assertEquals(1, manual.ticks, "the executor still ticks even with nothing to arbitrate");
        assertTrue(Double.isInfinite(arbiter.costTolerance(ctx)), "nothing active -> unbounded tolerance");
    }

    // --- who is driving, and a bid that goes silent -----------------------------------------------

    @Test
    void activeDriveNamesTheRunningInstinctAndClearsAtTheBoundary() {
        FakeInstinct a = new FakeInstinct("a", 0.5, () -> new Step("aRoot", 1, TaskStatus.SUCCESS));
        Arbiter arbiter = new Arbiter(List.of(a));
        assertTrue(arbiter.activeDrive().isEmpty(), "before the first tick nobody is driving");
        arbiter.tick(ctx);
        assertSame(a, arbiter.activeDrive().orElseThrow(),
                "the granted instinct itself — identity, which is what a caller holding one can compare");
        arbiter.tick(ctx); // the root reaches SUCCESS: a boundary
        assertTrue(arbiter.activeDrive().isEmpty(), "a finished root leaves nobody driving");
    }

    @Test
    void aManualOrderNamesNoActiveDrive() {
        // What keeps the wander mute from cancelling somebody else's work: an order nobody bid
        // for is not a drive, so no drive can be mistaken for it.
        Arbiter arbiter = new Arbiter(List.of());
        arbiter.executor().run(new Step("manual", Integer.MAX_VALUE, TaskStatus.SUCCESS), ctx);
        arbiter.tick(ctx);
        assertTrue(arbiter.executor().isBusy(), "the manual order is running");
        assertTrue(arbiter.activeDrive().isEmpty(), "...but it belongs to no instinct");
    }

    @Test
    void anOrderInstalledOverARunningDriveLeavesTheStaleDriveNamed() {
        // The trap: a manual order bypasses arbitration, so this still names the drive granted
        // before it until the arbiter ticks again — the staleness pressureLines() has too, and
        // why the wander mute cancels only while autonomy is on.
        FakeInstinct wander = new FakeInstinct("wander", 0.15, forever("roam"));
        Arbiter arbiter = new Arbiter(List.of(wander));
        arbiter.tick(ctx);
        assertSame(wander, arbiter.activeDrive().orElseThrow());

        arbiter.executor().run(new Step("manual", Integer.MAX_VALUE, TaskStatus.SUCCESS), ctx);
        assertSame(wander, arbiter.activeDrive().orElseThrow(),
                "the arbiter never heard about the order, so it still names wander");
    }

    @Test
    void aDriveThatGoesSilentIsNeverGrantedAgain() {
        // The wander mute in core terms: the drive keeps its place in the list and its root
        // factory, and only stops BIDDING — which is enough, because zero pressure is not a bid.
        FakeInstinct wander = new FakeInstinct("wander", 0.15, succeedsImmediately("roam"));
        Arbiter arbiter = new Arbiter(List.of(wander));
        arbiter.tick(ctx);
        arbiter.tick(ctx);
        assertEquals(2, wander.grantedRoots.size(), "the idle default re-grants itself a fresh roam");

        wander.pressure = 0.0; // muted
        for (int i = 0; i < 20; i++) {
            arbiter.tick(ctx);
        }
        assertEquals(2, wander.grantedRoots.size(), "a silent drive is never granted again");
        assertFalse(arbiter.executor().isBusy(), "and with nothing else bidding, they simply stand there");
        assertTrue(arbiter.activeDrive().isEmpty());
    }

    // --- cost tolerance ---------------------------------------------------------------------------

    @Test
    void costToleranceIsTheActiveDrivesOwn() {
        FakeInstinct rich = new FakeInstinct("rich", 0.7, forever("sat"));
        rich.budget = 60.0;
        FakeInstinct wander = new FakeInstinct("wander", 0.15, forever("roam"));
        wander.budget = 0.0;
        Arbiter arbiter = new Arbiter(List.of(rich, wander));
        assertTrue(Double.isInfinite(arbiter.costTolerance(ctx)), "before any tick, nothing active");

        arbiter.tick(ctx); // rich (0.7) wins
        assertEquals(60.0, arbiter.costTolerance(ctx), "the winner's budget, not a shared curve");

        // The same two budgets are read off whichever drive holds the wheel — a separate arbiter
        // rather than a preempt, because 0.15 never clears the PREEMPT bar to take it off `rich`.
        Arbiter idling = new Arbiter(List.of(wander));
        idling.tick(ctx);
        assertEquals(0.0, idling.costTolerance(ctx), "an idle saunter buys nothing");
    }

    // --- describe smoke --------------------------------------------------------------------------

    @Test
    void describeListsEachInstinctThenTheExecutor() {
        FakeInstinct eat = new FakeInstinct("eat", 0.7, forever("sat"));
        FakeInstinct wander = new FakeInstinct("wander", 0.15, forever("roam"));
        Arbiter arbiter = new Arbiter(List.of(eat, wander));
        arbiter.tick(ctx); // eat granted; its Step "sat" runs
        assertEquals("eat 0.70 (active)\nwander 0.15\nrunning: sat", arbiter.describe());
    }

    @Test
    void describeMarksACoolingInstinct() {
        FakeInstinct a = new FakeInstinct("a", 1.0, failsImmediately("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.5, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));
        arbiter.tick(ctx); // A fails -> cooldown 100; B not yet granted (idle at end of this tick)
        assertTrue(arbiter.describe().startsWith("a 1.00 (cooldown 100t)\nb 0.50"),
                "the cooling instinct is tagged with its remaining ticks:\n" + arbiter.describe());
    }

    // --- Flee: real-instinct scenes -----------------------------------------------------------

    /**
     * Mid-bite, a threat blows past both {@link Arbiter#stickiness()} and
     * {@link Arbiter#preempt()}: Flee cuts the chew off ({@code ConsumeItem}'s cancel aborts the
     * consumer) and takes the legs. Once it clears, the running leg still finishes — Eat is under
     * PREEMPT — and the meal it cut off sits out its fail cooldown before the next bite.
     */
    @Test
    void aCloseThreatPreemptsAMidChewEatWhichSitsOutItsCooldownBeforeTheNextBite() {
        Arbiter arbiter = new Arbiter(List.of(
                Drives.EAT, new WanderInstinct(), new FleeInstinct()));

        // Peckish (below PREEMPT) with bread in hand -> Eat outbids idle Wander and starts a bite.
        ctx.percepts.food("minecraft:bread", new FoodValue(5, 6.0F, false));
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:bread", 10, 64));
        ctx.percepts.metabolism.setFoodLevel(12); // hunger 1 - 12/20 = 0.4 -- PECKISH, under PREEMPT (0.6)

        arbiter.tick(ctx); // t1: Eat (0.4) beats Wander (0.15) and no-threat Flee (0.0); begins a bite
        assertEquals(1, ctx.consumer.beginCalls);
        assertTrue(arbiter.describe().contains("eat") && arbiter.describe().contains("(active)"), arbiter.describe());
        ctx.consumer.setState(ConsumeState.CONSUMING); // mid-chew, scripted like the body would report it

        // A threat close enough to push Flee to 0.9 -- well past PREEMPT and past Eat's 0.4.
        ctx.percepts.beings = List.of(FakePercepts.monsterAt(new Pos(5, 64, 0), 5.2, false)); // (16-5.2)/12 = 0.9

        arbiter.tick(ctx); // t2: Flee preempts mid-chew
        assertEquals(1, ctx.consumer.abortCalls, "the chew was cancelled -- ConsumeItem.cancel aborts it");
        assertTrue(arbiter.describe().contains("flee") && arbiter.describe().contains("(active)"), arbiter.describe());
        assertEquals(1, ctx.mover.moveToCalls, "FleeStep's GoTo takes the legs");
        assertEquals(dev.luizloyola.anima.core.nav.Gait.SPRINT, ctx.mover.lastGait,
                "the flee leg sprints");

        ctx.percepts.beings = List.of();
        ctx.mover.setState(MoveState.ARRIVED);
        arbiter.tick(ctx); // t3: GoTo SUCCEEDS -> the leg (FleeStep, no Idle) ends -> boundary
        assertFalse(arbiter.executor().isBusy(),
                "the leg finished this tick, but nothing is re-granted until the NEXT boundary");

        // t4..t102: Eat, cut off at t2, is out for exactly its 100 ticks although it tops Wander.
        for (int t = 4; t <= 2 + Instinct.DEFAULT_FAIL_COOLDOWN; t++) {
            arbiter.tick(ctx);
            assertEquals(1, ctx.consumer.beginCalls, "eat still cooling at tick " + t);
        }
        arbiter.tick(ctx); // t103: eligible again
        assertEquals(2, ctx.consumer.beginCalls, "eat bites again once its cooldown is served");
        assertTrue(arbiter.describe().contains("eat") && arbiter.describe().contains("(active)"), arbiter.describe());
    }

    /**
     * The loop this cooldown exists for, at the food level where a Person can no longer sprint
     * (6, so Eat bids 0.70). Walking away, the zombie stops closing and fear falls under Eat's
     * bid; stopping to eat, it closes again and fear jumps past it. Before, the two traded the
     * wheel every few ticks and neither a bite nor the flight ever finished (2026-09-26).
     */
    @Test
    void aHungryWalkerCutOffMidBiteKeepsFleeingInsteadOfStoppingToEatAgain() {
        Arbiter arbiter = new Arbiter(List.of(
                Drives.EAT, new WanderInstinct(), new FleeInstinct()));
        ctx.percepts.food("minecraft:bread", new FoodValue(5, 6.0F, false));
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:bread", 10, 64));
        ctx.percepts.metabolism.setFoodLevel(6);
        Pos behind = new Pos(8, 64, 0);

        ctx.percepts.beings = List.of(FakePercepts.monsterAt(behind, 7.0, true)); // 1.3 * 9/12 = 0.98
        arbiter.tick(ctx); // t1: Flee takes the wheel
        ctx.mover.setState(MoveState.MOVING);

        ctx.percepts.beings = List.of(FakePercepts.monsterAt(behind, 9.0, false)); // 7/12 = 0.58
        arbiter.tick(ctx); // t2: Eat's 0.70 beats Flee's 0.68 and preempts — Flee serves its 10
        assertEquals(1, ctx.consumer.beginCalls);
        ctx.consumer.setState(ConsumeState.CONSUMING);

        ctx.percepts.beings = List.of(FakePercepts.monsterAt(behind, 8.0, true)); // 1.3 * 8/12 = 0.87
        for (int t = 3; t <= 2 + FleeInstinct.FAIL_COOLDOWN; t++) {
            arbiter.tick(ctx);
            assertEquals(0, ctx.consumer.abortCalls, "flee still cooling at tick " + t);
        }
        arbiter.tick(ctx); // t13: Flee's 0.87 beats Eat's 0.80 and cuts the bite off
        assertEquals(1, ctx.consumer.abortCalls);
        ctx.mover.setState(MoveState.MOVING);

        // Walking away again: Eat outbids Flee exactly as it did at t2, but it is cooling now.
        ctx.percepts.beings = List.of(FakePercepts.monsterAt(behind, 9.0, false));
        for (int t = 14; t <= 13 + Instinct.DEFAULT_FAIL_COOLDOWN; t++) {
            arbiter.tick(ctx);
            assertEquals(1, ctx.consumer.beginCalls, "no second bite at tick " + t);
        }
        assertTrue(arbiter.describe().contains("flee 0.58 (active)"), arbiter.describe());

        arbiter.tick(ctx); // t114
        assertEquals(2, ctx.consumer.beginCalls, "hunger gets its turn once the cooldown is served");
    }

    /**
     * Nothing out-bids {@link FleeInstinct}, so it wins every re-arbitration; each re-grant builds
     * a FRESH {@code FleeStep} (never a cached tree), re-aimed at the threat's position NOW.
     */
    @Test
    void fleeChainsFreshReaimedLegsAsTheThreatMovesWhilePressureStaysHigh() {
        SpyingFlee flee = new SpyingFlee(new Random(11));
        Arbiter arbiter = new Arbiter(List.of(flee));
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(FakePercepts.monsterAt(new Pos(5, 64, 0), 5.0, false)); // east

        arbiter.tick(ctx); // t1: grant leg #1; its GoTo issues, aimed west
        assertEquals(1, flee.grantedRoots.size());
        assertTrue(ctx.mover.lastX < 0, "leg 1 runs west, away from the eastern threat");

        ctx.mover.setState(MoveState.ARRIVED); 
        ctx.percepts.beings = List.of(FakePercepts.monsterAt(new Pos(-5, 64, 0), 5.0, false)); // now west
        arbiter.tick(ctx); // t2: GoTo #1 SUCCEEDS -> boundary; re-grant is still next tick, not this one
        assertEquals(1, flee.grantedRoots.size(), "re-grant happens on the NEXT tick, not the boundary tick itself");

        arbiter.tick(ctx); // t3: idle -> a FRESH FleeStep, re-aimed at the CURRENT (now western) threat
        assertEquals(2, flee.grantedRoots.size());
        assertNotSame(flee.grantedRoots.get(0), flee.grantedRoots.get(1), "a fresh root each grant");
        assertTrue(ctx.mover.lastX > 0, "leg 2 re-aims east, away from the now-western threat");
    }
}
