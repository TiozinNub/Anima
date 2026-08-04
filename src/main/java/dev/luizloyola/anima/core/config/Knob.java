package dev.luizloyola.anima.core.config;

import java.util.Optional;

/**
 * Anima's own tunables, one constant per knob. Everything else in Anima's config stack derives from
 * this list — the JSON schema, the {@code /anima config} completions, the validation clamp, the
 * optional YACL screen — so a new tunable is a line here plus a one-line accessor beside the code
 * that reads it, and nothing anywhere reflects over field names.
 *
 * <p><b>These are limits, not defaults.</b> The dials of a mind are now
 * {@link dev.luizloyola.anima.core.agent.ProfileAspect}s, declared per species by the mod that
 * ships the body; keeping them here is why a rabbit saw as far as a settler. What is left is the
 * short list a species must not answer for itself:
 *
 * <ul>
 *   <li><b>{@code limits.*}</b> — work per agent per tick; the operator's ceiling over everybody,
 *       since a species that could raise its own could take a server down. Each doc line says what
 *       happens at saturation, because a cap degrades behaviour rather than breaking it.
 *   <li><b>{@code claims.*}</b> — the contract of a registry two agents share, so it belongs to the
 *       board between them rather than to either one.
 *   <li><b>{@code journal.*}</b> — a debugging facility and its disk use.
 * </ul>
 *
 * <p>A consuming mod's own tunables belong in its own enum and file; see {@link KnobSpec} and
 * {@link KnobSet}. The flee weights are not knobs at
 * all (entity ids are an open set, belonging to whoever ships a body), so they are their own
 * artifact per consumer ({@code DangerFile}).
 *
 * <p>Keys are dotted {@code snake_case}, the file nesting one object per segment, as Minecraft
 * itself moved to in 26.1.
 *
 * <p><b>Ranges are safety bounds, not taste.</b> {@code min}/{@code max} keep a hand-edited file
 * from producing a server that stalls (a million block reads per tick); {@link ConfigValues} clamps
 * rather than rejects, so one bad line degrades to a warning instead of failing the whole file.
 */
public enum Knob implements KnobSpec {

    // --- limits: what no species may spend more of than this ---------------------------------

    /** @see dev.luizloyola.anima.core.brain.knowledge.PoiSensorCore#readsPerTick() */
    READS_PER_TICK("limits.reads_per_tick", Kind.INT, 256, 1, 4096,
            "Block-read budget per agent per tick — the main throughput/TPS dial. At the cap, "
                    + "columns wait in the queue below and places are noticed later, never missed. "
                    + "The far sense is what sets the floor: one sweep of the skyline costs about "
                    + "25,000 reads at a 128-block reach, and it has to finish inside "
                    + "places.horizon_radius's refresh interval or a body never stops looking and "
                    + "never settles. Raise this with the reach, not after it."),
    /** @see dev.luizloyola.anima.core.brain.knowledge.PoiSensorCore#queueCap() */
    QUEUE_CAP("limits.queue_cap", Kind.INT, 1024, 16, 65_536,
            "How many un-probed columns may back up per agent before new sightings are dropped. "
                    + "At the cap an agent genuinely stops noticing things until it catches up, so "
                    + "raise this before raising reads_per_tick."),
    /** @see dev.luizloyola.anima.core.brain.knowledge.RegionGrowth#maxBlocks() */
    REGION_MAX_BLOCKS("limits.region_max_blocks", Kind.INT, 4096, 16, 16_384,
            "Block cap on one structure scan — a bound on the MEMORY one in-flight scan holds, "
                    + "not on throughput (reads_per_tick is the throughput dial; a bigger cap "
                    + "lets a scan run for more ticks, not for more work per tick). Hitting it "
                    + "marks the region partial, and partial is worse than it sounds: a tree "
                    + "whose crown fell outside the cut fails the crown test and is not "
                    + "remembered AT ALL. Set it above the biggest fused mass worth knowing — "
                    + "canopies weld 26-way, so one conifer stand is several trees' worth "
                    + "(a mega spruce alone is ~430 blocks). At the old 512 a Person standing "
                    + "INSIDE four touching mega spruces remembered two of them."),
    /** @see dev.luizloyola.anima.core.brain.knowledge.RegionCache#maxCells() */
    REGION_CACHE_CELLS("limits.region_cache_cells", Kind.INT, 65_536, 0, 1_048_576,
            "How much of the world's SHAPE one level remembers on every agent's behalf. Growing a "
                    + "structure is the most expensive thing perception does, and its answer is a "
                    + "fact about the world rather than anybody's opinion of it — so it is worked "
                    + "out once and lent to whoever comes past next, which is what stops fifty "
                    + "settlers in one wood running fifty identical scans of the same trees. "
                    + "Nobody becomes telepathic: a body still notices, remembers and forgets its "
                    + "own trees, it just no longer pays to re-measure one. Counted in cells "
                    + "rather than structures because a pumpkin is one and a fused spruce stand "
                    + "is thousands; the least recently visited go first. 0 turns it off."),
    /**
     * The aggregate ceiling — the one that actually protects a server, because it is the only one
     * that knows how many agents there are.
     *
     * @see dev.luizloyola.anima.core.brain.sense.RayPool
     */
    RAYS_PER_TICK("limits.rays_per_tick", Kind.INT, 512, 16, 16_384,
            "Total line-of-sight checks EVERY agent on the server may spend between them each "
                    + "tick, shared out fairly. At the ceiling, agents notice things later rather "
                    + "than not at all — refused checks are deferred, never dropped. Only bites "
                    + "when the population times the per-agent base below approaches it."),
    /** @see dev.luizloyola.anima.core.brain.sense.BeingSensorCore#rayBudgetBase() */
    RAY_BUDGET("limits.ray_budget", Kind.INT, 8, 1, 256,
            "Base line-of-sight checks per agent per tick. The effective budget scales up with the "
                    + "backlog (max of this and a quarter of the due work), so a 100-mob wave is "
                    + "noticed within ~4 ticks — deferred, never skipped. That elasticity is why "
                    + "the aggregate ceiling below exists."),

    // --- claims: the contract of a registry two agents share ----------------------------------

    /** @see dev.luizloyola.anima.core.brain.board.SiteClaims#ttlTicks() */
    CLAIM_TTL_TICKS("claims.ttl_ticks", Kind.INT, 600, 20, 72_000,
            "How long a site claim outlives its last heartbeat before another agent may take the "
                    + "spot (20 ticks = 1 second)."),

    // --- journal: a debugging facility and its disk use ---------------------------------------

    /** @see dev.luizloyola.anima.core.log.JournalService#defaultMaxEntriesPerPerson() */
    JOURNAL_MAX_ENTRIES("journal.max_entries_per_person", Kind.INT, 256, 16, 8192,
            "Ring size per agent. Older entries are evicted once it fills."),
    /** @see dev.luizloyola.anima.core.log.JournalService#defaultMaxAgeTicks() */
    JOURNAL_MAX_AGE_TICKS("journal.max_age_ticks", Kind.INT, 12_000, 20, 1_728_000,
            "Age cutoff for journal entries (default 10 minutes of game time)."),
    /** Read by the mod-side journal store's periodic sweep. */
    JOURNAL_SWEEP_INTERVAL("journal.sweep_interval_ticks", Kind.INT, 600, 20, 72_000,
            "How often the journal store evicts aged-out entries."),
    /** Read by the mod-side journal file sink when a world loads. */
    JOURNAL_FILE_SINK("journal.file_sink", Kind.BOOL, 0, 0, 1,
            "Mirror each agent's journal to logs/anima/agent-<id>.log on disk.");

    private final String key;
    private final Kind kind;
    private final double def;
    private final double min;
    private final double max;
    private final String doc;

    Knob(String key, Kind kind, double def, double min, double max, String doc) {
        this.key = key;
        this.kind = kind;
        this.def = def;
        this.min = min;
        this.max = max;
        this.doc = doc;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public double def() {
        return def;
    }

    @Override
    public double min() {
        return min;
    }

    @Override
    public double max() {
        return max;
    }

    @Override
    public String doc() {
        return doc;
    }

    /** The knob with this dotted key, or empty — the lookup behind {@code config get}/{@code set}. */
    public static Optional<Knob> byKey(String key) {
        for (Knob knob : values()) {
            if (knob.key.equals(key)) {
                return Optional.of(knob);
            }
        }
        return Optional.empty();
    }
}
