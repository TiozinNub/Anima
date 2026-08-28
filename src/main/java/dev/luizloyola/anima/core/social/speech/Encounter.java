package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A world-scoped conversation over 2+ participants (a list from day one so three never needs
 * surgery). Passive: the participating brains tick it, the transcript is the state machine,
 * and the last line is the reason it ended.
 */
public final class Encounter {

    private final UUID id;
    private final List<AgentId> participants;
    private final long openedAt;
    private final List<Utterance> transcript = new ArrayList<>();
    private long closedAt = -1;

    public Encounter(UUID id, List<AgentId> participants, long openedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.participants = List.copyOf(participants);
        this.openedAt = openedAt;
        if (this.participants.size() < 2) {
            throw new IllegalArgumentException("a conversation needs at least two");
        }
    }

    public UUID id() { return id; }

    public List<AgentId> participants() { return participants; }

    public boolean includes(AgentId who) { return participants.contains(who); }

    /** The one other party of a two-body encounter; empty for self or a stranger to it. */
    public Optional<AgentId> other(AgentId self) {
        if (!includes(self)) {
            return Optional.empty();
        }
        for (AgentId each : participants) {
            if (!each.equals(self)) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }

    public long openedAt() { return openedAt; }

    public List<Utterance> transcript() { return Collections.unmodifiableList(transcript); }

    public Optional<Utterance> last() {
        return transcript.isEmpty() ? Optional.empty()
                : Optional.of(transcript.get(transcript.size() - 1));
    }

    /** When something last happened — the opening counts, so staleness works on silence too. */
    public long lastActivityTick() {
        return transcript.isEmpty() ? openedAt : transcript.get(transcript.size() - 1).tick();
    }

    public void append(Utterance u) {
        if (closed()) {
            throw new IllegalStateException("the record is finished — nothing speaks after " + id);
        }
        transcript.add(Objects.requireNonNull(u, "utterance"));
    }

    public boolean hasSystem(String act, AgentId subject) {
        String key = subject.toString();
        for (Utterance u : transcript) {
            if (u.system() && u.act().equals(act) && key.equals(u.payload().get(Utterance.SUBJECT))) {
                return true;
            }
        }
        return false;
    }

    public void close(long tick) {
        if (!closed()) {
            closedAt = tick;
        }
    }

    public boolean closed() { return closedAt >= 0; }

    public long closedAt() { return closedAt; }
}
