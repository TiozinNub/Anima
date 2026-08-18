package dev.luizloyola.anima.mod.webdebug;

/**
 * How often a frame is allowed out, in wall-clock time rather than in ticks.
 *
 * <p><b>The dashboard is paced by the clock because the tick is not one.</b> A frame per
 * {@code END_SERVER_TICK} is twenty a second on a vanilla server and unbounded under
 * {@code /tick sprint}, where the server runs flat out: every one of those ticks was rendering the
 * whole roster to JSON <em>inside the tick</em> and pushing it at a browser that then parsed and
 * drew it. The tab locked up and the sprint it was watching ran slower for having been watched.
 *
 * <p>The gate is a <b>deadline that advances by the gap</b>, not a floor measured from the last
 * frame. The difference only shows above the cap, and it is the whole reason for the field: at 100
 * ticks a second a floor drops every second tick and delivers 50 frames — under the 60 it is
 * enforcing, and worse than the rate it was asked for.
 *
 * <p>Not thread-safe, and does not need to be: the only caller is the server tick thread.
 */
final class WebPace {

    private final long gapNanos;

    /** The earliest {@link System#nanoTime} the next frame may go, once {@link #armed}. */
    private long due;

    /** Whether a frame has ever gone. {@code nanoTime}'s origin is arbitrary, so zero means nothing. */
    private boolean armed;

    WebPace(long perSecond) {
        this.gapNanos = 1_000_000_000L / perSecond;
    }

    /**
     * Whether a frame is due now — and if so, books it. Call once per tick and render only when it
     * says yes.
     *
     * @param now {@link System#nanoTime}, compared by difference: the value itself has no meaning.
     */
    boolean due(long now) {
        if (armed && now - due < 0) {
            return false;
        }
        long next = due + gapNanos;
        // Clamped to now when the deadline is already past — an unfrozen world, a long pause, a
        // server that was down. Otherwise the gate would owe every frame the gap says it missed
        // and pay them out back to back, which is the flood this exists to stop.
        due = armed && next - now > 0 ? next : now + gapNanos;
        armed = true;
        return true;
    }
}
