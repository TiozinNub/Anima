package dev.luizloyola.anima.core.config;

import java.util.Optional;

/**
 * Anima's own tunables: one constant per knob — dotted key, type, default, legal range. The file
 * schema, the {@code /anima config} completions, the clamp and the optional YACL screen all derive
 * from this list, so a new tunable is a line here plus a one-line accessor.
 *
 * <p><b>Limits, not defaults.</b> Per-species dials are
 * {@link dev.luizloyola.anima.core.agent.ProfileAspect}s, in the file of the mod that ships the
 * body. What stays is what a species must not answer for itself: {@code limits.*}, the operator's
 * ceiling on work per agent per tick; {@code claims.*}, the contract of a registry two agents
 * share; {@code journal.*}, disk use; {@code terrain.*}, how the ground is judged flat and usable,
 * which is a world's to tune rather than a species' (Luiz, 2026-09-25). A consumer's own tunables
 * go in its own enum and file
 * ({@link KnobSpec}, {@link KnobSet}); flee weights are not knobs at all, entity ids being an open
 * set ({@code DangerFile}).
 *
 * <p>Keys are dotted {@code snake_case}, nested one object per segment as Minecraft has since
 * 26.1. {@code min}/{@code max} are safety bounds against a hand-edited file stalling a server,
 * not taste; {@link ConfigValues} clamps rather than rejects, so one bad line is a warning instead
 * of a failed file.
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
    /** @see dev.luizloyola.anima.core.brain.knowledge.PlaceIndex#maxCells() */
    PLACE_INDEX_CELLS("limits.place_index_cells", Kind.INT, 65_536, 0, 1_048_576,
            "How many cells of RECOGNISED THINGS one level remembers for everybody — the index "
                    + "that answers \"whose tree is this leaf?\" in one lookup, so a body that "
                    + "walks up to a wood somebody has already been through pays nothing to know "
                    + "what stands in it. Sized independently of region_cache_cells because it "
                    + "holds each cell ONCE, keyed by the thing that owns it, where the scan cache "
                    + "holds a whole mass per seed anybody happened to start from: a 147-oak wood "
                    + "measured 8,767 cells here against 62,615 there. Only things seen WHOLE are "
                    + "kept — a tree straddling the edge of a cut-short scan is provisional and is "
                    + "re-looked-at rather than lent. 0 turns it off."),
    /** @see dev.luizloyola.anima.core.brain.knowledge.ReadPool#totalPerTick() */
    READS_PER_TICK_TOTAL("limits.reads_per_tick_total", Kind.INT, 0, 0, 1_048_576,
            "Total block reads EVERY agent on the server may spend between them each tick, shared "
                    + "out fairly. The per-agent wallet above caps one mind; this caps the server, "
                    + "which is the only cap a population can outgrow — 300 agents at the default "
                    + "wallet is 76,800 reads a tick and nothing above stops it. Measured on a "
                    + "real wood, looking at places was very nearly the whole server thread at 150 "
                    + "walkers, and about nine reads in ten were the skyline sweep. At the ceiling "
                    + "agents notice things later rather than not at all, and they degrade in the "
                    + "right order for free: the near field is served first, so a squeezed body "
                    + "still sees the tree beside it and merely takes longer to make out the "
                    + "ridge. 0 turns the ceiling off, which is the old per-agent-only behaviour."),
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
    /** @see dev.luizloyola.anima.mod.nav.PathfinderService#inThread() */
    PATHFINDER_IN_THREAD("limits.pathfinder_in_thread", Kind.BOOL, 0, 0, 1,
            "Run the path search on the server thread instead of on a worker. Off, a search costs "
                    + "the tick nothing and its answer arrives when it arrives — which is "
                    + "WALL-CLOCK time, while the rest of a mind counts TICKS. At 20 ticks a "
                    + "second those two agree closely enough to ignore; under /tick sprint they do "
                    + "not, and a search that spanned a fiftieth of a tick spans dozens, so a task "
                    + "counting ticks against legs that are merely still thinking gives up — a "
                    + "body that looks unable to cross a small clearing. On, the answer is always "
                    + "in hand the same tick it was asked for, at the price of the search landing "
                    + "inside the tick: one is bounded (4096 expansions, single-figure "
                    + "milliseconds), but a crowd re-planning on the same tick is not. Chiefly a "
                    + "development dial — on to fast-forward a world without the warp, off for a "
                    + "populated server."),
    /** @see dev.luizloyola.anima.mod.nav.PathfinderService#enclosure */
    ENCLOSURE_REACH("limits.enclosure_reach", Kind.INT, 24, 12, 64,
            "Half-width of the box captured to judge the space a body stands in, when its last "
                    + "walk's capture does not cover it: a house wider than about twice this less "
                    + "ten reads as open ground. Baked on the server thread, so it costs with the "
                    + "square of this; the walk's own capture is used whenever it covers."),

    // --- claims: the contract of a registry two agents share ----------------------------------

    /** @see dev.luizloyola.anima.core.brain.board.SiteClaims#ttlTicks() */
    CLAIM_TTL_TICKS("claims.ttl_ticks", Kind.INT, 600, 20, 72_000,
            "How long a site claim outlives its last heartbeat before another agent may take the "
                    + "spot (20 ticks = 1 second)."),

    // --- social: the contract of a shared conversation record ---------------------------------

    /** @see dev.luizloyola.anima.core.social.speech.SpeechEngine */
    SOCIAL_ENCOUNTER_TURN_CAP("social.encounter_turn_cap", Kind.INT, 60, 4, 1_000,
            "How many transcript lines an encounter may reach before only farewells are left "
                    + "to say. A backstop, not a target — conversations should end themselves."),
    SOCIAL_ENCOUNTER_TICK_CAP("social.encounter_tick_cap", Kind.INT, 6_000, 100, 72_000,
            "How long (ticks) an encounter may stay open before only farewells are left to "
                    + "say. 6000 ticks is five minutes."),
    SOCIAL_ENCOUNTER_STALE_TICKS("social.encounter_stale_ticks", Kind.INT, 1_200, 100, 72_000,
            "How long (ticks) an open encounter may sit silent before whoever returns to it "
                    + "closes it as forgotten instead of resuming it."),
    SOCIAL_ENCOUNTER_RETENTION_TICKS("social.encounter_retention_ticks", Kind.INT, 72_000, 1_200, 1_728_000,
            "How long (ticks) a closed encounter is kept — the debugging artifact, and what "
                    + "overheard lines resolve against. 72000 ticks is three in-game days."),

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
            "Mirror each agent's journal to logs/anima/<run>/agent-<id>.log on disk."),
    /** Read by the mod-side journal file sink at boot. */
    JOURNAL_KEEP_RUNS("journal.keep_runs", Kind.INT, 10, 1, 1000,
            "How many past runs of per-agent journal files to keep. Older run folders are "
                    + "deleted at boot; a dead agent's file is moved to graveyard/ first."),
    /** Read by the mod-side burial, once, as an agent dies. */
    JOURNAL_DEATH_TAIL("journal.death_tail_entries", Kind.INT, 64, 0, 256,
            "How many of an agent's last journal lines are copied into its grave, where they "
                    + "outlive the ring and the restart. This is what makes a death readable "
                    + "afterwards — the fall, the fight or the slow starve that led into it. 0 "
                    + "keeps the grave a bare tombstone. 64 rather than a couple of dozen because "
                    + "a body standing near others spends most of its lines noticing them: 24 "
                    + "measured out at twelve seconds, nearly all of it peer chatter."),

    /** Read by the mod-side journal wiring at boot and on every config change. */
    JOURNAL_SENSE_PEER("journal.sense_peer", Kind.BOOL, 0, 0, 1,
            "Log every flip of a perceived being's state. Off by default: with twenty settlers "
                    + "in one camp this is three quarters of every line written. Turn it on when "
                    + "perception itself is what you are debugging."),
    /** Read by the mod-side journal wiring at boot and on every config change. */
    JOURNAL_SENSE_OVERLOOKED("journal.sense_overlooked", Kind.BOOL, 0, 0, 1,
            "Log each place passed over as not worth remembering. Off by default: it repeats the "
                    + "same cells for as long as a body stands near them."),
    /** Read by the mod-side journal wiring at boot and on every config change. */
    JOURNAL_MIND("journal.mind", Kind.BOOL, 1, 0, 1,
            "Log a need crossing one of its declared levels — company going lonely, food going "
                    + "peckish. On by default: about a dozen lines per settler per day."),
    /** Read by the mod-side journal wiring at boot and on every config change. */
    JOURNAL_OP("journal.op", Kind.BOOL, 1, 0, 1,
            "Log operator commands that change something, in the journal of every agent they "
                    + "touch. On by default: read-only commands are never logged."),
    /** Read by the mod-side journal wiring at boot and on every config change. */
    JOURNAL_PROJECT_OFFER("journal.project_offer", Kind.BOOL, 1, 0, 1,
            "Log why a body was offered no work, when the reason changes. On by default: it only "
                    + "speaks when the answer moves, so a benched settler writes one line, not one "
                    + "per tick."),

    // --- terrain: how the ground is judged, by /anima terrain and whatever chooses a site ---

    /** @see dev.luizloyola.anima.core.terrain.TerrainRules#configured() */
    TERRAIN_SMOOTH_RADIUS("terrain.smooth_radius", Kind.INT, 6, 1, 16,
            "Half-width of the window the ground is smoothed over, in blocks. Flatness is judged "
                    + "across it: wider turns short rises into roughness instead of slope."),
    TERRAIN_MAX_SLOPE("terrain.max_slope", Kind.DOUBLE, 0.2, 0, 1,
            "The steepest smoothed rise per block still called flat — 0.2 is one block up in "
                    + "five. The dial that matters most: on the forest world 0.125 called 34% of "
                    + "dry land flat, 0.2 called 55%."),
    TERRAIN_MAX_ROUGH("terrain.max_rough", Kind.DOUBLE, 2, 0, 16,
            "How far one column may stand above or below the smoothed ground and still be "
                    + "flat: 2 lets a two-block hole or bump through."),
    TERRAIN_STEEP_ANGLE("terrain.steep_angle", Kind.DOUBLE, 45, 1, 89,
            "The gentlest climb a hillside makes, in degrees: at 45 with steep_height 8, the "
                    + "ground climbs 8 blocks within 8."),
    TERRAIN_STEEP_HEIGHT("terrain.steep_height", Kind.INT, 8, 1, 64,
            "How far the ground climbs at steep_angle to be a hillside rather than a bump: 8 is "
                    + "enough hill to dig a base into."),
    TERRAIN_STEEP_ABOVE_LAND("terrain.steep_above_land", Kind.DOUBLE, 4, 0, 64,
            "How far a hillside's top stands above the average ground within 32 blocks, water "
                    + "included. Tells a hillside from the wall of a pit, whose top is only the "
                    + "level of the land around it."),
    TERRAIN_CLIFF_HEIGHT("terrain.cliff_height", Kind.INT, 4, 2, 64,
            "The smallest drop from one column to the next called a cliff — near vertical by "
                    + "construction, 76° at 4. Four is the first drop a body can neither climb "
                    + "nor fall without harm."),
    TERRAIN_AREA_SIZE("terrain.area_size", Kind.INT, 9, 1, 63,
            "A flat area or clearing must fit a square this wide, so that it is wide rather than "
                    + "merely large. Rounded up to odd."),
    TERRAIN_USED_MARGIN("terrain.used_margin", Kind.INT, 4, 0, 32,
            "How far past a block somebody placed (a crafting table, a chest, a path) the "
                    + "ground counts as used."),
    TERRAIN_SITE_SIZE("terrain.site_size", Kind.INT, 17, 3, 63,
            "The side of a building site, in blocks. Rounded up to odd."),
    TERRAIN_MAX_TILT("terrain.max_tilt", Kind.DOUBLE, 0.1, 0, 1,
            "The steepest plane a building site may lie on — 0.1 is one block in ten. Stricter "
                    + "than max_slope because it is judged across the whole site, where a single "
                    + "step and a steady slope no longer look alike."),
    TERRAIN_TREE_COST("terrain.tree_cost", Kind.DOUBLE, 20, 0, 1000,
            "One tree to fell, in blocks of digging and filling, when building sites are ranked."),

    // --- webdebug: the browser debug UI, off unless asked for -----------------------------

    /** @see dev.luizloyola.anima.mod.webdebug.WebDebugger */
    WEB_ENABLED("webdebug.enabled", Kind.BOOL, 0, 0, 1,
            "Start the web debugger automatically when a world loads. This is the AUTO-START "
                    + "switch only — /anima webdebug start runs it for one session whatever "
                    + "this says, which is what you want for an occasional look. A development "
                    + "tool: it exposes every agent's mind and its commands drive them."),
    /** @see dev.luizloyola.anima.mod.webdebug.WebDebugger#port() */
    WEB_PORT("webdebug.port", Kind.INT, 25_599, 1024, 65_535,
            "Port the web debugger listens on. Change it when something else already holds this "
                    + "one; the server logs the address to open either way."),
    /** @see dev.luizloyola.anima.mod.webdebug.WebDebugger#host() */
    WEB_HOST("webdebug.host", Kind.STRING, "127.0.0.1", 1, 64,
            "Address the web debugger binds to. 127.0.0.1 keeps it on this machine, which is the "
                    + "only setting the security story is written for: it exposes every agent's "
                    + "mind and its commands drive them, and the allowed browser names below are "
                    + "the ONLY thing guarding it. Anything else — a LAN address, or 0.0.0.0 for "
                    + "every interface — puts that on the network in the clear, over plain HTTP, "
                    + "where a key is readable by anything on the path. The server logs a warning "
                    + "when it binds anywhere but loopback. A non-loopback address also stops the "
                    + "page being a secure context, which some browser APIs need."),
    /**
     * Who may see this world through a browser. Written by {@code /anima webdebug allow},
     * not by hand.
     *
     * @see dev.luizloyola.anima.mod.webdebug.WebBrowsers
     */
    WEB_ALLOWED_NAMES("webdebug.allowed_names", Kind.LIST, "", 0, 512,
            "Browsers allowed to see this world. Each browser makes up its own three-word name, "
                    + "keeps it, and asks to be let in; an operator allows it with /anima "
                    + "webdebug allow, which writes it here. Nothing is allowed automatically "
                    + "and the list starts empty. Delete a line — or run /anima webdebug "
                    + "remove — to take a browser off the list; it can ask again. A name here "
                    + "works like a password: anything that presents one sees every agent here "
                    + "and can command them."),
    /**
     * Text rather than a bundled asset: the page served from here is a stub, and the UI itself is
     * fetched from this URL. @see dev.luizloyola.anima.mod.webdebug.WebDebugger
     */
    WEB_APP_URL("webdebug.app_url", Kind.STRING, "https://anima-debugger.tioz.in/app.v1.js",
            8, 512,
            "Where the web debugger's UI is loaded from. The mod serves only a stub page: the "
                    + "stub's origin is localhost, so its calls to this server are same-origin — "
                    + "which is what avoids the mixed-content and Local Network Access rules that "
                    + "block a hosted page from reaching 127.0.0.1. The site therefore sees one "
                    + "asset request and no world data at all. Point it at a local dev server to "
                    + "work on the UI itself.");

    private final String key;
    private final Kind kind;
    private final double def;
    private final double min;
    private final double max;
    private final String defText;
    private final String doc;

    Knob(String key, Kind kind, double def, double min, double max, String doc) {
        this(key, kind, def, min, max, "", doc);
    }

    /** A {@link Kind#STRING} knob: {@code min}/{@code max} bound the LENGTH, not the value. */
    Knob(String key, Kind kind, String defText, double minLength, double maxLength, String doc) {
        this(key, kind, 0.0, minLength, maxLength, defText, doc);
    }

    Knob(String key, Kind kind, double def, double min, double max, String defText, String doc) {
        this.key = key;
        this.kind = kind;
        this.def = def;
        this.min = min;
        this.max = max;
        this.defText = defText;
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
    public String defText() {
        return defText;
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
