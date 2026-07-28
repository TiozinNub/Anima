package dev.luizloyola.anima.core.log;

import java.util.Objects;

/**
 * One line of a person's log, minus the person. A row of the debug journal
 * ({@code <tick> <category> event - detail}); the {@code Bob} column is not stored because an entry
 * is always held under its owner's {@code AgentId} (the ring's key, the file's name), and the name
 * is resolved for display at render time.
 *
 * @param tick     game-time of the event, stamped by {@link JournalService} from its injected
 *                 clock. Absolute, not a per-person age, so two persons' logs line up and deltas
 *                 read straight off.
 * @param category which subsystem spoke (see {@link Category}).
 * @param event    the short what/where — {@code "wander (10,10,10)"}, {@code "stray"}. Free-form;
 *                 each emitter writes in its own vocabulary, since the body knows a
 *                 {@code DamageSource} the core never will.
 * @param detail   the outcome/how — {@code "success 10 nodes"}, {@code "took 4 damage (lava) now
 *                 15/20"}. Free-form and optional; a {@code null} is normalised to {@code ""}.
 */
public record Entry(long tick, Category category, String event, String detail) {
    public Entry {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(event, "event");
        detail = detail == null ? "" : detail;
    }
}
