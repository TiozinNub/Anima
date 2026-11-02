package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The real {@link Speech} — one body's half of the machinery, over a shared {@link Encounters}
 * roster. Final: a body wanting a scripted variant (see {@code FakeSpeech}) wraps one rather than
 * extending it, which keeps every consumer honestly going through the port.
 */
public final class SpeechEngine implements Speech {

    private final AgentId self;
    private final LongSupplier now;
    private final Encounters roster;
    private final Supplier<Chooser> chooser;
    private final Supplier<Caps> caps;
    private final Listener listener;

    /**
     * No profile parameter, deliberately: every number a conversation runs on arrives through
     * {@link Caps}, whoever assembled it. That is what lets a PLAYER hold one of these — a player
     * has no species to read aspects off, and borrows its counterpart's (rung 7, 2026-09-11).
     */
    public SpeechEngine(AgentId self, LongSupplier now,
            Encounters roster, Supplier<Chooser> chooser, Supplier<Caps> caps, Listener listener) {
        this.self = Objects.requireNonNull(self, "self");
        this.now = Objects.requireNonNull(now, "now");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.chooser = Objects.requireNonNull(chooser, "chooser");
        this.caps = Objects.requireNonNull(caps, "caps");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * The tunables a body's conversation runs on — {@code turnCap}/{@code tickCap} bound how long
     * a record may run (by line count and by duration), {@code staleGapTicks} how long silence is
     * tolerated before {@link #current()} calls it dead, {@code patienceTicks} how long an
     * unanswered obligation is tolerated before {@link #expiredObligation} names the snubber,
     * {@code greetedForTicks} how long a closed conversation between the same two still counts.
     */
    public record Caps(int turnCap, long tickCap, long staleGapTicks, int patienceTicks,
            long greetedForTicks) {
        public Caps(int turnCap, long tickCap, long staleGapTicks, int patienceTicks) {
            this(turnCap, tickCap, staleGapTicks, patienceTicks, 0);
        }
    }

    /** Notified of every record opened, line said and record closed — the social log's hook in. */
    public interface Listener {
        /** A fresh record, opened by {@code self}'s engine. Not called for a rejoined one. */
        default void opened(Encounter e, AgentId self, Opening opening) {
        }

        /** {@code self} could not open a record with {@code other}: one of them is talking. */
        default void busy(AgentId self, AgentId other) {
        }

        /** @param why the chooser's account of the line, or null for one nobody explained */
        void said(Encounter e, Utterance u, @Nullable String why);

        void closed(Encounter e);

        Listener NONE = new Listener() {
            @Override
            public void said(Encounter e, Utterance u, @Nullable String why) {
            }

            @Override
            public void closed(Encounter e) {
            }
        };
    }

    @Override
    public Optional<Encounter> current() {
        Optional<Encounter> found = roster.openFor(self);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Encounter e = found.get();
        if (roster.stale(e, now.getAsLong(), caps.get().staleGapTicks())) {
            // STALE ends the record, so writing it also closes it — one call does both jobs
            // the brief calls out as "forgotten": the line and the close.
            system(e, SpeechActs.STALE, self);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    @Override
    public Optional<Encounter> join(BeingId other, Opening opening) {
        long tick = now.getAsLong();
        boolean fresh = roster.openFor(self).isEmpty();
        Optional<Encounter> joined = roster.join(self, other.asPerson(), tick);
        if (joined.isEmpty()) {
            listener.busy(self, other.asPerson());
            return joined; // one of the two is already talking — the roster's rule, not ours
        }
        Encounter e = joined.get();
        if (fresh) {
            listener.opened(e, self, opening);
        }
        if (e.transcript().isEmpty() && opening != Opening.QUIET) {
            // Either party may open first; the record reads the same both ways — credited to
            // whoever actually hailed, never to whichever body's engine happened to call join().
            AgentId hailer = opening == Opening.I_HAILED ? self : other.asPerson();
            Utterance hail = new Utterance(hailer, SpeechActs.HAIL.key(), Map.of(), tick);
            e.append(hail);
            listener.said(e, hail, null);
        }
        return joined;
    }

    @Override
    public void say(Encounter e, Chooser.Line line) {
        SpeechAct act = line.act();
        // Read BEFORE appending — a line of one's own discharges whatever was pending, so asking
        // afterwards would never find the goodbye this one answers.
        boolean closes = act.ends() && (!act.obliges() || answersItsOwnKind(e, act));
        Utterance u = new Utterance(self, act.key(), line.payload(), now.getAsLong());
        e.append(u);
        listener.said(e, u, line.why());
        if (closes) {
            close(e);
        }
    }

    /**
     * The handshake half of {@link SpeechAct#ends()}: an ending act that obliges is said twice —
     * the first opens the obligation and leaves the record open for the answer, the second IS
     * that answer and closes it. One that does not oblige closes on the spot.
     */
    private boolean answersItsOwnKind(Encounter e, SpeechAct act) {
        return Picker.pendingOn(e, self).map(p -> p.act().equals(act.key())).orElse(false);
    }

    @Override
    public void system(Encounter e, SpeechAct act, AgentId subject) {
        if (e.hasSystem(act.key(), subject)) {
            return;
        }
        Utterance u = Utterance.system(act.key(), subject, now.getAsLong());
        e.append(u);
        listener.said(e, u, null);
        if (act.ends()) {
            close(e);
        }
    }

    @Override
    public void close(Encounter e) {
        if (e.closed()) {
            return;
        }
        roster.close(e, now.getAsLong());
        listener.closed(e);
    }

    @Override
    public boolean maySpeak(Encounter e) {
        return Picker.maySpeak(e, self);
    }

    @Override
    public Optional<AgentId> expiredObligation(Encounter e) {
        return Picker.expiredObligation(e, self, now.getAsLong(), caps.get().patienceTicks());
    }

    @Override
    public boolean lapsedFarewell(Encounter e) {
        return Picker.lapsedFarewell(e, self, now.getAsLong(), caps.get().patienceTicks());
    }

    /**
     * The cap {@link Picker#applicable} filters {@code e} by right now: the configured turn cap,
     * or 0 once the record has outrun {@code tickCap} — the duration cap reuses the turn-cap
     * filter, narrowing to the same "only an ending is on offer" set a line count already produces.
     *
     * <p>Static because the {@code /anima chat} readout has to reproduce exactly this filter for an
     * agent whose body is not loaded, and so has no engine to ask.
     */
    public static int turnCap(Encounter e, long now, int turnCap, long tickCap) {
        return now - e.openedAt() > tickCap ? 0 : turnCap;
    }

    @Override
    public Chooser.Turn turn(Encounter e) {
        Caps c = caps.get();
        int turnCap = turnCap(e, now.getAsLong(), c.turnCap(), c.tickCap());
        List<SpeechAct> applicable = Picker.applicable(e, self, turnCap);
        Optional<Utterance> pending = Picker.pendingOn(e, self);
        Optional<AgentId> counterpart = counterpart(e);
        // In two-party, pendingOn(e, other) returns a line NOT authored by other — which here
        // means authored by self: exactly what self asked of the counterpart and nobody of
        // theirs has answered since. For 3+ parties this conflates askers (it names whichever
        // outside party spoke last, not necessarily self); acceptable today, nothing reads it
        // as an identity claim.
        Optional<Utterance> awaiting = counterpart.flatMap(other -> Picker.pendingOn(e, other));
        List<Utterance> earlier = counterpart.map(other -> earlier(other, c.greetedForTicks()))
                .orElse(List.of());
        return new Chooser.Turn(e, applicable, pending, awaiting, greeted(e) || !earlier.isEmpty(),
                counterpart, earlier);
    }

    @Override
    public Optional<AgentId> counterpart(Encounter e) {
        for (AgentId participant : e.participants()) {
            if (!participant.equals(self)) {
                return Optional.of(participant);
            }
        }
        return Optional.empty();
    }

    @Override
    public Chooser chooser() {
        return chooser.get();
    }

    /**
     * Whether self's greeting is behind it: said, or overtaken. Once either side has said anything
     * past the hail and the greetings, a hello comes too late — answering a request and then saying
     * "Hello" read backwards (decision: Luiz, 2026-10-03, "after the first message, no hello is
     * needed anymore").
     */
    /**
     * What self and {@code other} said in conversations closed within {@code windowTicks}: met
     * again that soon, they do not say hello twice nor bring the same small talk up again
     * (decision: Luiz, 2026-11-02). Empty with a window of 0.
     */
    private List<Utterance> earlier(AgentId other, long windowTicks) {
        if (windowTicks <= 0) {
            return List.of();
        }
        List<Utterance> lines = new ArrayList<>();
        for (Encounter past : roster.closedBetween(self, other, now.getAsLong() - windowTicks)) {
            lines.addAll(past.transcript());
        }
        return lines;
    }

    private boolean greeted(Encounter e) {
        for (Utterance u : e.transcript()) {
            if (u.system()) {
                continue;
            }
            if (u.act().equals(SpeechActs.GREETING.key())) {
                if (self.equals(u.author())) {
                    return true;
                }
            } else if (!u.act().equals(SpeechActs.HAIL.key())) {
                return true;
            }
        }
        return false;
    }
}
