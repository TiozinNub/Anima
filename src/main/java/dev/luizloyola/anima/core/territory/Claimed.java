package dev.luizloyola.anima.core.territory;

import dev.luizloyola.anima.core.social.PartyId;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One change to a party's territory, or one refused — the claim log's line.
 *
 * <p>A refusal is kept and logged like a change: "the house was not built because the chunk east of
 * HOME is another party's" is the line a debugging session looks for.
 *
 * @param blocking the chunks that caused a refusal; empty when it was granted
 */
public record Claimed(long tick, PartyId party, SortedSet<ChunkKey> added,
                      SortedSet<ChunkKey> removed, Reason why, Refusal refusal,
                      SortedSet<ChunkKey> blocking) {

    public enum Refusal {
        NONE,
        /** A chunk asked for is another party's. */
        OTHER_PARTY,
        /** A chunk asked for is held by something outside Anima — an OPAC claim. */
        TAKEN,
        /** The area would not be one piece. */
        DETACHED,
        /** A chunk to release is not this party's. */
        NOT_OURS
    }

    public Claimed {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(why, "why");
        Objects.requireNonNull(refusal, "refusal");
        added = frozen(added);
        removed = frozen(removed);
        blocking = frozen(blocking);
    }

    static Claimed granted(long tick, PartyId party, Set<ChunkKey> added, Set<ChunkKey> removed,
                           Reason why) {
        return new Claimed(tick, party, new TreeSet<>(added), new TreeSet<>(removed), why,
                Refusal.NONE, new TreeSet<>());
    }

    static Claimed refused(long tick, PartyId party, Reason why, Refusal refusal,
                           Set<ChunkKey> blocking) {
        return new Claimed(tick, party, new TreeSet<>(), new TreeSet<>(), why, refusal,
                new TreeSet<>(blocking));
    }

    public boolean granted() {
        return refusal == Refusal.NONE;
    }

    /** Whether it changed anything — a granted claim of chunks already held does not. */
    public boolean changed() {
        return !added.isEmpty() || !removed.isEmpty();
    }

    /** One line, the same in a journal, the world log and a command's reply. */
    public String describe() {
        StringBuilder line = new StringBuilder();
        if (granted()) {
            if (!added.isEmpty()) {
                line.append("claimed ").append(added.size()).append(' ').append(added);
            }
            if (!removed.isEmpty()) {
                line.append(line.length() == 0 ? "" : ", ").append("released ")
                        .append(removed.size()).append(' ').append(removed);
            }
            if (line.length() == 0) {
                line.append("nothing new");
            }
        } else {
            line.append("refused (").append(refusal.name().toLowerCase(Locale.ROOT))
                    .append(") ").append(blocking);
        }
        return line.append(" — ").append(why).toString();
    }

    /** A copy, so a record handed to a listener cannot be changed under the log. */
    private static SortedSet<ChunkKey> frozen(SortedSet<ChunkKey> chunks) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(chunks, "chunks")));
    }
}
