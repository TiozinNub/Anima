package dev.luizloyola.anima.mod.webdebug;

import java.util.List;

/**
 * A section of the frame, and how often it is allowed to be rebuilt.
 *
 * <p><b>A clock gates one or more top-level keys, and the grouping is not the JSON's.</b> Cadence
 * is deliberately decoupled from shape: the wire stays flat, and a key can be moved onto a
 * different clock without the browser noticing.
 *
 * <p>The rates are constants rather than knobs. A knob here would be a tuning surface for a problem
 * nobody has had, and one more value that can be wrong in a config file nobody remembers editing.
 *
 * <p>There is no heartbeat member: the heartbeat builds nothing, so it is a floor on publishing
 * rather than a section — see {@link WebClocks#beat}.
 */
enum WebClock {

    /** Four numbers, and the mspt jitter in them is the whole point. */
    HEALTH(20, "health"),

    /**
     * Positions and distances. Nobody reads a table faster than this.
     *
     * <p>{@code dead} rides here too, not on {@link #SLOW}: it is the grave count, and the grave
     * rows are built on this same clock in {@code agents()}. Split across two rates, the footer's
     * census and the dead table could disagree by one for as long as the slower of the two takes
     * to catch up.
     */
    ROSTER(10, "agents", "dead"),

    /** The expensive one: the journal tail, the inventory, the knowledge counts, the needs. */
    DETAIL(4, "detail"),

    /** A hundred samples covering five seconds — the ring loses nothing between frames. */
    CHART(4, "samples"),

    /** Rarely moves at all; change detection suppresses nearly every one of these. */
    SLOW(2, "players", "layers", "actingAs");

    private final int perSecond;
    private final List<String> keys;

    WebClock(int perSecond, String... keys) {
        this.perSecond = perSecond;
        this.keys = List.of(keys);
    }

    int perSecond() {
        return perSecond;
    }

    /** The frame keys this clock owns. */
    List<String> keys() {
        return keys;
    }
}
