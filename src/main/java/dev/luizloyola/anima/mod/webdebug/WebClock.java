package dev.luizloyola.anima.mod.webdebug;

/**
 * A section of the frame, and how often it is allowed to be rebuilt.
 *
 * <p><b>A clock gates one or more top-level keys, and the grouping is not the JSON's.</b> Cadence
 * is deliberately decoupled from shape: the wire stays flat, and a key can be moved onto a
 * different clock without the browser noticing.
 *
 * <p><b>{@link WebSnapshot#build} is the authority for which key rides which clock</b> — it names
 * them where it builds them. Each constant below says which keys it owns, and that is documentation
 * admitting to being documentation: it can drift, and only reading {@code build} settles it. There
 * was a {@code keys()} method here once, pinned by a test; nothing in production ever called it, so
 * moving a key in {@code build} changed the wire and the test went on passing. A guard that cannot
 * bite is worse than none.
 *
 * <p>The rates are constants rather than knobs. A knob here would be a tuning surface for a problem
 * nobody has had, and one more value that can be wrong in a config file nobody remembers editing.
 *
 * <p>There is no heartbeat member: the heartbeat builds nothing, so it is a floor on publishing
 * rather than a section — see {@link WebClocks#beat}.
 */
enum WebClock {

    /** {@code health}: four numbers, and the mspt jitter in them is the whole point. */
    HEALTH(20),

    /**
     * {@code agents} and {@code dead}: positions and distances. Nobody reads a table faster.
     *
     * <p>{@code dead} rides here too, not on {@link #SLOW}: it is the grave count, and it is taken
     * in the same walk of the directory that builds the grave rows. Split across two rates, the
     * footer's census and the dead table could disagree by one for as long as the slower of the
     * two takes to catch up.
     */
    ROSTER(10),

    /** {@code detail}: the expensive one — journal tail, inventory, knowledge counts, needs. */
    DETAIL(4),

    /** {@code samples}: a hundred samples covering five seconds; the ring loses nothing between. */
    CHART(4),

    /** {@code players}, {@code layers}, {@code actingAs}: change detection suppresses nearly all. */
    SLOW(2);

    private final int perSecond;

    WebClock(int perSecond) {
        this.perSecond = perSecond;
    }

    int perSecond() {
        return perSecond;
    }
}
