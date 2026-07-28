package dev.luizloyola.anima.core.log;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.Objects;

/**
 * A person's-eye view onto the shared {@link JournalService} — a flyweight pinning one
 * {@link AgentId}, so callers write {@code journal.record(BRAIN, "wander", "start")} instead of
 * repeating the id. It is only the {@code (service, id)} pair, so any number may be handed out at
 * no cost.
 *
 * <p>What {@code BrainContext.journal()} returns for a loaded Person; offline sim binds one per
 * {@code AgentId} it simulates, no entity required.
 */
public record AgentJournal(JournalService service, AgentId id) {
    public AgentJournal {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(id, "id");
    }

    /** Record one line for this person — see {@link JournalService#record}. */
    public void record(Category category, String event, String detail) {
        service.record(id, category, event, detail);
    }

    /** This person's last {@code max} lines, oldest-first — see {@link JournalService#recent}. */
    public List<Entry> recent(int max) {
        return service.recent(id, max);
    }
}
