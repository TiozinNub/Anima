package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

/**
 * One server's whole allowance of block reads per tick, shared out between every agent on it —
 * the same machinery {@code RayPool} gives sight; why it is a second pool rather than a share of
 * that one is in {@link ReadBudget}.
 *
 * <p><b>Fair by credit, not by arrival.</b> Agents tick in entity order, so a counter drained
 * first-come leaves whoever ticks last systematically incurious. This is a deficit round robin:
 * every agent accrues an equal share of the ceiling each tick whether or not it asks, spends
 * against its own balance, and carries what it did not use — so an agent that was cut short holds
 * the credit next tick, wherever it sits in the order. Perception is bursty, and banking the quiet
 * ticks is what pays for the loud one, capped at {@link #BURST_TICKS} ticks' worth.
 *
 * <p><b>The ceiling is hard</b>: balances alone could let many agents burst on one tick, so a
 * running total is kept as well and nobody is granted past it.
 *
 * <p>Pure core: told the population, reads its numbers from suppliers. {@code ReadPools} holds one
 * per server and keeps the population current.
 */
public final class ReadPool implements ReadBudget {

    /**
     * How many ticks' worth of unused allowance an agent may bank. Small on purpose: enough to
     * spend hard on walking into new ground, not so much that a long-idle agent hoards a spike.
     */
    static final int BURST_TICKS = 4;

    /** The aggregate ceiling; 0 means unmetered. */
    public static int totalPerTick() {
        return Config.get().i(Knob.READS_PER_TICK_TOTAL);
    }

    private final IntSupplier capacity;
    /**
     * Weakly keyed on purpose: a body that unloads takes its balance with it, so nothing has to
     * remember to tell the pool about a death, a chunk unload or a dimension change.
     */
    private final Map<Object, Double> balances = new WeakHashMap<>();

    private int population = 1;
    private long tick = Long.MIN_VALUE;
    private int spentThisTick;
    /** Whether the ceiling actually refused anybody since the last time this was read. */
    private boolean cancelling;

    /**
     * @param capacity total block reads every agent on this server may spend between them, per
     *     tick. Read on use, so lowering it takes effect at once. A capacity of 0 or less is
     *     "no ceiling". That is what turns the pool off from the config file.
     */
    public ReadPool(IntSupplier capacity) {
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

    /** Reads each agent accrues per tick: an equal cut of the ceiling, never below one. */
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
        int ceiling = capacity.getAsInt();
        if (ceiling <= 0) {
            return wanted; // switched off: the per-agent wallet is the only cap
        }
        // Roll over before the trivial cases: a tick nobody asks on still accrues for everyone.
        if (now != tick) {
            beginTick(now, ceiling);
        }
        if (wanted <= 0) {
            return 0;
        }
        // A body that has never asked starts with one tick's worth, so its first look never waits.
        double balance = balances.computeIfAbsent(agent, a -> (double) share());
        int room = Math.max(0, ceiling - spentThisTick);
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

    @Override
    public void refund(Object agent, int unused, long now) {
        if (unused <= 0 || now != tick) {
            return; // a refund from a tick that has already rolled over is not ours to give back
        }
        Double balance = balances.get(agent);
        if (balance == null) {
            return;
        }
        double share = Math.max(1, capacity.getAsInt() / (double) population);
        // Capped like any other credit: an idle body must not out-spend the bank ceiling the
        // moment it finds something.
        balances.put(agent, Math.min(share * BURST_TICKS, balance + unused));
        spentThisTick = Math.max(0, spentThisTick - unused);
    }

    /**
     * Accrues everyone's share and starts a fresh per-tick total. Skipped ticks credit once, not
     * once per tick elapsed, so a server back from a hitch is not handed a second's worth of reads
     * to spend at once.
     */
    private void beginTick(long now, int ceiling) {
        tick = now;
        spentThisTick = 0;
        double share = Math.max(1, ceiling / (double) population);
        double bank = share * BURST_TICKS;
        balances.replaceAll((agent, balance) -> Math.min(bank, balance + share));
    }

    /** How many agents currently hold a balance — for tests and readouts. */
    public int tracked() {
        return balances.size();
    }
}
