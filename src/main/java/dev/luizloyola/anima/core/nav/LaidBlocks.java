package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Every block a route laid and left standing (docs/superpowers/specs/2026-09-28-bridging-design.md):
 * decks, which stay as the party's and are counted when walked, and pillars, which are temporary —
 * a later climber's ladder, and cleaned up by the body that goes down them.
 *
 * <p>Grouped in <b>runs</b>: the decks of one crossing, the blocks of one pillar. A pillar a climber
 * moves one column over stays the same run, block by block.
 *
 * <p>A block a player breaks is not told to anyone here; whoever reads a row checks the world first
 * and forgets the rows that no longer match ({@link #remove}).
 *
 * <p>Pure core and single-threaded by contract (the server thread); persistence is the mod layer's.
 */
public final class LaidBlocks {

    public enum Kind { DECK, PILLAR }

    /** One laid block: where, what, which kind, who laid it for which party, when, in which run. */
    public record Row(Pos at, String block, Kind kind, @Nullable AgentId layer, @Nullable PartyId party,
                      long tick, int run) {
    }

    /**
     * A run of blocks laid together: a crossing's decks, a pillar's blocks. {@code walked} counts
     * the walks that crossed a deck run — what tells a builder where a proper bridge would pay.
     */
    public record Run(int id, Kind kind, int walked, long lastWalked) {
    }

    private final Map<Pos, Row> rows = new LinkedHashMap<>();
    private final Map<Integer, Run> runs = new LinkedHashMap<>();
    private int nextRun = 1;
    private Runnable listener = () -> { };

    /** Called after every change — the persisting layer marks itself dirty here. */
    public void onChange(Runnable listener) {
        this.listener = listener;
    }

    /** A new run of {@code kind}, empty until something is laid in it. */
    public int open(Kind kind) {
        int id = this.nextRun++;
        this.runs.put(id, new Run(id, kind, 0, 0L));
        this.listener.run();
        return id;
    }

    /** Records a laid block; a row already at that cell is replaced. */
    public void lay(Pos at, String block, Kind kind, @Nullable AgentId layer, @Nullable PartyId party,
                    long tick, int run) {
        if (!this.runs.containsKey(run)) {
            this.runs.put(run, new Run(run, kind, 0, 0L));
            this.nextRun = Math.max(this.nextRun, run + 1);
        }
        this.rows.put(at, new Row(at, block, kind, layer, party, tick, run));
        this.listener.run();
    }

    public Optional<Row> at(Pos at) {
        return Optional.ofNullable(this.rows.get(at));
    }

    /** Forgets a laid block — broken, eaten on the way down, or no longer there. */
    public boolean remove(Pos at) {
        Row gone = this.rows.remove(at);
        if (gone == null) {
            return false;
        }
        if (this.rows.values().stream().noneMatch(row -> row.run() == gone.run())) {
            this.runs.remove(gone.run());
        }
        this.listener.run();
        return true;
    }

    /** A pillar block moved one column over by a climber: the same run, the same layer. */
    public void move(Pos from, Pos to, long tick) {
        Row row = this.rows.remove(from);
        if (row == null) {
            return;
        }
        this.rows.put(to, new Row(to, row.block(), row.kind(), row.layer(), row.party(), tick,
                row.run()));
        this.listener.run();
    }

    /** One more walk across a run of decks. */
    public void walked(int run, long tick) {
        Run was = this.runs.get(run);
        if (was != null) {
            this.runs.put(run, new Run(was.id(), was.kind(), was.walked() + 1, tick));
            this.listener.run();
        }
    }

    /**
     * The pillar blocks inside the box, as {@link Pathfinder}'s packed cells — what a request carries
     * so a search can climb beside one or go down one.
     */
    public Set<Long> pillarsWithin(int x1, int y1, int z1, int x2, int y2, int z2) {
        Set<Long> cells = new HashSet<>();
        for (Row row : this.rows.values()) {
            Pos at = row.at();
            if (row.kind() == Kind.PILLAR && at.x() >= x1 && at.x() <= x2 && at.y() >= y1
                    && at.y() <= y2 && at.z() >= z1 && at.z() <= z2) {
                cells.add(Pathfinder.pack(at.x(), at.y(), at.z()));
            }
        }
        return cells;
    }

    /** A cell packed the way {@link PathRequest#pillars()} carries it. */
    public static long cell(int x, int y, int z) {
        return Pathfinder.pack(x, y, z);
    }

    public List<Row> rows() {
        return new ArrayList<>(this.rows.values());
    }

    public List<Run> runs() {
        return new ArrayList<>(this.runs.values());
    }

    public @Nullable Run run(int id) {
        return this.runs.get(id);
    }

    /** Restores a saved run's counts — the persisting layer's, on load. */
    public void restore(Run run) {
        this.runs.put(run.id(), run);
        this.nextRun = Math.max(this.nextRun, run.id() + 1);
    }
}
