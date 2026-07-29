package dev.luizloyola.anima.core.brain.sense;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

/**
 * One server's whole allowance of sight rays per tick, shared out between every agent on it.
 *
 * <p><b>Fair by credit, not by arrival.</b> Agents tick in entity order, so a counter drained
 * first-come leaves the ones at the end systematically blind — silently, which is miserable to
 * diagnose. This is a deficit round robin: every agent accrues an equal share each tick whether or
 * not it asks, spends against its own balance, and carries what it did not use.
 *
 * <p>The carry-over is the elasticity that notices a mob wave quickly, capped at
 * {@link #BURST_TICKS} ticks' worth. The per-tick ceiling is hard on top of it: a running total is
 * kept and nobody is granted past it. Whatever is refused is deferred by the caller, never dropped.
 *
 * <p>Pure core: it is told the population and reads its numbers from suppliers. {@code RayPools}
 * holds one per server and keeps the population current.
 */
public final class RayPool implements RayBudget {

    /**
     * How many ticks' worth of unused share an agent may bank. Small on purpose: enough that an
     * idle body can spend hard the moment a crowd arrives, not so much that it hoards a spike
     * nobody planned for.
     */
    static final int BURST_TICKS = 4;

    private final IntSupplier capacity;
    /**
     * Weakly keyed on purpose: a body that unloads takes its balance with it, so nothing has to
     * remember to tell the pool about a death, a chunk unload or a dimension change. A leak here
     * would be one entry per body that ever existed on a long-running server.
     */
    private final Map<Object, Double> balances = new WeakHashMap<>();

    private int population = 1;
    private long tick = Long.MIN_VALUE;
    private int spentThisTick;
    /** Whether the ceiling actually refused anybody since the last time this was read. */
    private boolean cancelling;

    /**
     * @param capacity total rays every agent on this server may spend between them, per tick.
     *     Read on use, so lowering it takes effect at once.
     */
    public RayPool(IntSupplier capacity) {
        this.capacity = capacity;
    }

    /** How many agents are sharing the pool. Set by whoever watches bodies load and unload. */
    public void population(int population) {
        this.population = Math.max(1, population);
    }

    /** The population last set — what the projection is computed against. */
    public int population() {
        return population;
    }

    /** Rays each agent accrues per tick: an equal cut of the ceiling, never below one. */
    public int share() {
        return Math.max(1, capacity.getAsInt() / population);
    }

    /**
     * Whether the ceiling has actually refused anybody recently — the difference between
     * "projected to be heavy" and "degrading now". Cleared by {@link #clearCancelling()}.
     */
    public boolean cancelling() {
        return cancelling;
    }

    /** Resets the flag, so the next refusal is a fresh edge. */
    public void clearCancelling() {
        cancelling = false;
    }

    @Override
    public int grant(Object agent, int wanted, long now) {
        // Roll over before the trivial cases: a tick nobody asks on still accrues for everyone.
        if (now != tick) {
            beginTick(now);
        }
        if (wanted <= 0) {
            return 0;
        }
        // A body that has never asked starts with one tick's worth, so its first look never waits.
        double balance = balances.computeIfAbsent(agent, a -> (double) share());
        int room = Math.max(0, capacity.getAsInt() - spentThisTick);
        int granted = (int) Math.min(Math.min(wanted, balance), room);
        if (granted > 0) {
            balances.put(agent, balance - granted);
            spentThisTick += granted;
        }
        if (granted < wanted) {
            cancelling = true;
        }
        return granted;
    }

    /**
     * Accrues everyone's share and starts a fresh per-tick total. Skipped ticks credit once, not
     * once per tick elapsed: a server that hitched should come back with agents ready to look
     * around, not a second's worth of rays to spend at once.
     */
    private void beginTick(long now) {
        tick = now;
        spentThisTick = 0;
        double share = share();
        double ceiling = share * BURST_TICKS;
        balances.replaceAll((agent, balance) -> Math.min(ceiling, balance + share));
    }

    /** How many agents currently hold a balance — for tests and readouts. */
    public int tracked() {
        return balances.size();
    }
}
