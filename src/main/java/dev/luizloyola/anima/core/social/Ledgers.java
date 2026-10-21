package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Every party's three {@link Ledger}s: itemized lines whose sum a reader compares with its own
 * threshold. Nothing here decides anything.
 *
 * <p><b>Two kinds of line, kept apart</b> (social foundations §7): <em>standing</em> lines are
 * recomputed from the party's state on every read and never stored; <em>fading</em> lines come from
 * marks left when something happened, each worth its full points at first and nothing after its
 * days. Storing a standing condition, or recomputing an event, is how such numbers rot.
 *
 * <p><b>Weighed by the reader.</b> A mark stores only what happened and when. Its points and how long
 * it lasts are the reading body's own aspects, so two species read one party differently and a
 * retuned species retunes the marks already left.
 *
 * <p>Pure core, single-threaded by contract (the server thread); persistence is the mod layer's.
 */
public final class Ledgers {

    public static final long DAY_TICKS = 24_000L;

    /** Something that happened to a party, under the key of the rule that weighs it. */
    public record Mark(Ledger ledger, String key, long since) {
    }

    /** A fading line: each mark under {@code key} is worth {@code points}, falling to 0 over {@code days}. */
    public record Fading(Ledger ledger, String key, ProfileAspect points, ProfileAspect days) {
    }

    /** A standing line, read from the party's state as it is now; empty when it has nothing to say. */
    public interface Standing {
        Ledger ledger();

        /** A lang key, the line's label in the readout. */
        String key();

        OptionalDouble points(PartyId party, AgentProfile reader);
    }

    /** One line of a reading. {@code count} is how many marks a fading line sums, 0 for a standing one. */
    public record Line(String key, double points, int count) {
        public boolean fading() {
            return count > 0;
        }
    }

    public record Reading(Ledger ledger, List<Line> lines) {
        public Reading {
            lines = List.copyOf(lines);
        }

        public double total() {
            return lines.stream().mapToDouble(Line::points).sum();
        }

        /** The line that counts most against — what a refusal names. */
        public Optional<Line> strongestAgainst() {
            return lines.stream().filter(line -> line.points() < 0).min(Comparator.comparingDouble(Line::points));
        }
    }

    private final Map<PartyId, List<Mark>> marks = new HashMap<>();
    private final List<Fading> fading = new ArrayList<>();
    private final List<Standing> standing = new ArrayList<>();

    public void fades(Fading rule) {
        fading.add(rule);
    }

    public void stands(Standing line) {
        standing.add(line);
    }

    /**
     * Records that something happened to {@code party} now, and drops its marks no reader could still
     * weigh: older than the most days their rule's aspect allows any species.
     */
    public void mark(PartyId party, Ledger ledger, String key, long now) {
        List<Mark> list = marks.computeIfAbsent(party, p -> new ArrayList<>());
        list.removeIf(mark -> spent(mark, now));
        list.add(new Mark(ledger, key, now));
    }

    /** A party that ceased to exist. Whether it had marks. */
    public boolean forget(PartyId party) {
        return marks.remove(party) != null;
    }

    public List<Mark> marks(PartyId party) {
        return Collections.unmodifiableList(marks.getOrDefault(party, List.of()));
    }

    /** Every party with marks — for saving. */
    public Map<PartyId, List<Mark>> all() {
        return Collections.unmodifiableMap(marks);
    }

    /** The load path: a mark as it was saved, with no pruning. */
    public void restore(PartyId party, Mark mark) {
        marks.computeIfAbsent(party, p -> new ArrayList<>()).add(mark);
    }

    /** {@code ledger} for {@code party} as {@code reader} weighs it: standing lines, then fading ones. */
    public Reading read(Ledger ledger, PartyId party, AgentProfile reader, long now) {
        List<Line> lines = new ArrayList<>();
        for (Standing line : standing) {
            if (line.ledger() == ledger) {
                line.points(party, reader).ifPresent(points -> lines.add(new Line(line.key(), points, 0)));
            }
        }
        List<Mark> left = marks.getOrDefault(party, List.of());
        for (Fading rule : fading) {
            if (rule.ledger() != ledger) {
                continue;
            }
            double points = 0;
            int count = 0;
            for (Mark mark : left) {
                if (mark.ledger() == ledger && mark.key().equals(rule.key())) {
                    double share = 1.0 - (double) (now - mark.since()) / (reader.i(rule.days()) * DAY_TICKS);
                    if (share > 0) {
                        points += reader.d(rule.points()) * Math.min(1.0, share);
                        count++;
                    }
                }
            }
            if (count > 0) {
                lines.add(new Line(rule.key(), points, count));
            }
        }
        return new Reading(ledger, lines);
    }

    /**
     * The company line: lonely members count +1, members above their comfortable band −1, the rest 0,
     * averaged — so a large party is not loud just for being large. Empty with nobody to ask.
     */
    public static OptionalDouble companyScore(Collection<String> levels) {
        if (levels.isEmpty()) {
            return OptionalDouble.empty();
        }
        double sum = 0;
        for (String level : levels) {
            sum += switch (level) {
                case "lonely" -> 1.0;
                case "crowded" -> -1.0;
                default -> 0.0;
            };
        }
        return OptionalDouble.of(sum / levels.size());
    }

    private boolean spent(Mark mark, long now) {
        for (Fading rule : fading) {
            if (rule.ledger() == mark.ledger() && rule.key().equals(mark.key())) {
                return now - mark.since() > rule.days().max() * DAY_TICKS;
            }
        }
        return false; // a consumer's rule not registered yet: keep it for when it is
    }
}
