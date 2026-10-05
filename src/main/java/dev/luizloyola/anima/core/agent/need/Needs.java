package dev.luizloyola.anima.core.agent.need;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Consumer;

/**
 * Everything one body feels, in one place — the roster of its {@link Gauge}s (the food organ is
 * {@code Metabolism}, which once had this name).
 *
 * <ul>
 *   <li>{@link #tick()} is the one tick site, so a new need cannot invent its own beat.</li>
 *   <li>{@link #describe()}, the {@code needs} command and the debug HUD list what a body feels
 *       without knowing what any of it is.</li>
 *   <li>Fidelia declares a pet's own needs without touching this class.</li>
 * </ul>
 *
 * <p><b>The read is unified; the write is not, and must not be.</b> What moves a gauge arrives as a
 * typed call on the gauge itself; a single {@code tick(everything)} would grow a field for every
 * gauge in every mod.
 *
 * <p>Agent-scoped and single-threaded by contract.
 */
public final class Needs {

    /** Insertion-ordered: a readout lists needs in the order the body declared them. */
    private final Map<NeedKind, Gauge> gauges = new LinkedHashMap<>();

    /**
     * Ticks a new level must hold before it counts as a crossing. A gauge hovering on a boundary
     * otherwise narrated every flip, and company, fed by proximity, lines and faces, hovers.
     * Only the crossing waits: {@link Gauge#level()} and everything that reads it do not.
     */
    static final int HOLD_TICKS = 100; // five seconds

    /**
     * Per kind, the level last announced and the one waiting out {@link #HOLD_TICKS}. Absent for a
     * kind never yet ticked, which is what makes the first sighting a seed rather than a crossing
     * out of nothing. Not saved: a load re-seeds it, so a crossing pending at the save goes unsaid.
     */
    private final Map<NeedKind, Watch> watches = new HashMap<>();

    private static final class Watch {
        NeedLevel settled;
        NeedLevel pending;
        int held;

        Watch(NeedLevel settled) {
            this.settled = settled;
        }
    }

    /** @see #onCrossing */
    private final List<Consumer<Crossing>> crossingListeners = new ArrayList<>();

    /**
     * A gauge has moved from one declared level to another and stayed there {@link #HOLD_TICKS}.
     * The boundaries are {@link NeedKind}'s own, so a gauge wandering inside a band says nothing.
     */
    public record Crossing(NeedKind kind, NeedLevel from, NeedLevel to, double pressure) {
    }

    /**
     * Notified on every level crossing, never on a jitter inside one band or across a boundary
     * and back within {@link #HOLD_TICKS}. {@code core} cannot reach a journal, so the mod layer
     * installs the listener here — the same shape as {@code JournalService.subscribe}.
     */
    public void onCrossing(Consumer<Crossing> listener) {
        crossingListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Declares that this body has this gauge. Chainable, for building a whole roster in a field
     * initializer.
     *
     * @throws IllegalStateException when a gauge of that kind is already registered
     */
    public Needs add(Gauge gauge) {
        Gauge existing = gauges.putIfAbsent(gauge.kind(), gauge);
        if (existing != null) {
            throw new IllegalStateException(
                    "this body already has a \"" + gauge.kind().key() + "\" gauge");
        }
        return this;
    }

    public boolean has(NeedKind kind) {
        return gauges.containsKey(kind);
    }

    public Optional<Gauge> gauge(NeedKind kind) {
        return Optional.ofNullable(gauges.get(kind));
    }

    /**
     * The same, as the concrete type that knows how to MOVE it — {@code gauge(COMPANY,
     * Company.class)} — or empty when this body has no such gauge, or has one of another type under
     * that key.
     *
     * <p>{@link Gauge} is read-only: a need moves by a typed call named for what
     * happened to the body ({@code eat(bread)}), never an {@code add(0.1)}. The amounts belong to
     * the gauge and its species aspects, the only place they can be tuned.
     */
    public <G extends Gauge> Optional<G> gauge(NeedKind kind, Class<G> type) {
        Gauge gauge = gauges.get(kind);
        return type.isInstance(gauge) ? Optional.of(type.cast(gauge)) : Optional.empty();
    }

    /** That gauge's reading in its own units, or {@code 0} for a need this body does not have. */
    public double value(NeedKind kind) {
        Gauge gauge = gauges.get(kind);
        return gauge == null ? 0.0 : gauge.value();
    }

    public Optional<NeedLevel> level(NeedKind kind) {
        Gauge gauge = gauges.get(kind);
        return gauge == null ? Optional.empty() : Optional.ofNullable(gauge.level());
    }

    /**
     * How badly that need wants attention, {@code 0} for a need this body does not have — so an
     * instinct bidding on a need this body lacks never fires, and need not ask.
     */
    public double pressure(NeedKind kind) {
        Gauge gauge = gauges.get(kind);
        return gauge == null ? 0.0 : gauge.pressure();
    }

    /** Every gauge this body has, in the order it declared them. */
    public Collection<Gauge> all() {
        return Collections.unmodifiableCollection(gauges.values());
    }

    /**
     * Advance every gauge one tick. Called once per body tick; a gauge that is a view over
     * something the body ticks elsewhere does nothing here.
     *
     * <p>Also where a level crossing is noticed: {@link #onCrossing} fires once a level other than
     * the last announced one has held for {@link #HOLD_TICKS}. The very first tick a kind is seen
     * only seeds its {@link Watch} — otherwise a body would announce a crossing into the level it
     * was born at, the moment it spawns.
     */
    public void tick() {
        for (Gauge gauge : gauges.values()) {
            gauge.tick();
            NeedLevel level = gauge.level();
            if (level == null) {
                continue; // a need with no declared levels has nothing to cross
            }
            Watch watch = watches.get(gauge.kind());
            if (watch == null) {
                watches.put(gauge.kind(), new Watch(level));
                continue;
            }
            if (level == watch.settled) {
                watch.pending = null;
                continue;
            }
            if (level != watch.pending) {
                watch.pending = level;
                watch.held = 0;
            }
            if (++watch.held >= HOLD_TICKS) {
                Crossing crossing = new Crossing(gauge.kind(), watch.settled, level, gauge.pressure());
                watch.settled = level;
                watch.pending = null;
                for (Consumer<Crossing> listener : crossingListeners) {
                    listener.accept(crossing);
                }
            }
        }
    }

    /** Every gauge's own line, joined. */
    public String describe() {
        StringJoiner joined = new StringJoiner(" | ");
        for (Gauge gauge : gauges.values()) {
            joined.add(gauge.describe());
        }
        return gauges.isEmpty() ? "no needs" : joined.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
