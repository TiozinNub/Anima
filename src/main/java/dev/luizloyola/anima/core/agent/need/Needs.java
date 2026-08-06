package dev.luizloyola.anima.core.agent.need;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

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

    /**
     * How full that gauge is, or {@code 0} for a need this body does not have — the same answer a
     * body with an empty gauge would give, and harmless because nothing acts on a level alone.
     */
    public double level(NeedKind kind) {
        Gauge gauge = gauges.get(kind);
        return gauge == null ? 0.0 : gauge.level();
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
     */
    public void tick() {
        for (Gauge gauge : gauges.values()) {
            gauge.tick();
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
