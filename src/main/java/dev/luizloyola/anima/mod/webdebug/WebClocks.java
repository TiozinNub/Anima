package dev.luizloyola.anima.mod.webdebug;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One {@link WebPace} per {@link WebClock}, plus the two things a set of them needs that a single
 * pace does not: a way for a click to jump the queue, and a floor under how quiet the feed may go.
 *
 * <p><b>Clocks pace the steady state; a click is not the steady state.</b> Without
 * {@link #force}, expanding a card would sit on its waiting text for up to a quarter of a second —
 * the change would measure better and feel worse, which is the failure it is here to prevent.
 *
 * <p>Read and written on the server tick thread, <b>except {@link #force}</b>, which is called from
 * an HTTP handler. That one field is atomic; nothing else here needs to be.
 */
final class WebClocks {

    /** The longest the feed may say nothing at all. @see #beat */
    private static final long BEAT_NANOS = 1_000_000_000L;

    private final Map<WebClock, WebPace> paces = new EnumMap<>(WebClock.class);

    /** Clocks a watch change has forced, as a bit per ordinal. Written by HTTP threads. */
    private final AtomicInteger forced = new AtomicInteger();

    private long lastPublished;

    /** Whether anything has ever been published. {@code nanoTime}'s origin is arbitrary. */
    private boolean published;

    WebClocks() {
        for (WebClock clock : WebClock.values()) {
            paces.put(clock, new WebPace(clock.perSecond()));
        }
    }

    /**
     * The clocks whose sections are to be built this tick.
     *
     * <p><b>Every pace is asked, not just the ones that might be due</b>, because asking is what
     * advances a deadline — skipping one would leave it owing the frames it missed and paying them
     * out back to back.
     */
    EnumSet<WebClock> due(long now) {
        int jumped = forced.getAndSet(0);
        EnumSet<WebClock> out = EnumSet.noneOf(WebClock.class);
        for (WebClock clock : WebClock.values()) {
            boolean onTime = paces.get(clock).due(now);
            if (onTime || (jumped & (1 << clock.ordinal())) != 0) {
                out.add(clock);
            }
        }
        return out;
    }

    /** Builds {@code clock}'s section on the next tick whatever its deadline says. Any thread. */
    void force(WebClock clock) {
        forced.updateAndGet(bits -> bits | (1 << clock.ordinal()));
    }

    /**
     * Whether a heartbeat is owed — asked only when nothing else survived the tick.
     *
     * <p>It is what keeps <em>nothing changed</em> from looking like <em>nothing is arriving</em>.
     * A frozen or unwatched world publishes no section at all, and a browser cannot tell those two
     * apart from the socket; twenty-five bytes a second buys the distinction outright.
     */
    boolean beat(long now) {
        return !published || now - (lastPublished + BEAT_NANOS) >= 0;
    }

    /** Records that a frame went out, for whatever reason. Resets the heartbeat floor. */
    void published(long now) {
        lastPublished = now;
        published = true;
    }
}
