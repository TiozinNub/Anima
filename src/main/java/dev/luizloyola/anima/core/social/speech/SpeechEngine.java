package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The real {@link Speech} — one body's half of the machinery, over a shared {@link Encounters}
 * roster. Final: a body wanting a scripted variant (see {@code FakeSpeech}) wraps one rather than
 * extending it, which keeps every consumer honestly going through the port.
 */
public final class SpeechEngine implements Speech {

    private final AgentId self;
    /**
     * Unread until Task 8 wires {@code ProfileAspect.SOCIAL_PATIENCE_TICKS} — until then patience
     * travels through {@link Caps#patienceTicks()} instead. Kept as a constructor parameter now
     * so wiring it in later touches no call site.
     */
    private final Supplier<AgentProfile> profile;
    private final LongSupplier now;
    private final Encounters roster;
    private final Supplier<Chooser> chooser;
    private final Supplier<Caps> caps;
    private final Listener listener;

    public SpeechEngine(AgentId self, Supplier<AgentProfile> profile, LongSupplier now,
            Encounters roster, Supplier<Chooser> chooser, Supplier<Caps> caps, Listener listener) {
        this.self = Objects.requireNonNull(self, "self");
        this.profile = Objects.requireNonNull(profile, "profile");
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
     * unanswered obligation is tolerated before {@link #expiredObligation} names the snubber.
     */
    public record Caps(int turnCap, long tickCap, long staleGapTicks, int patienceTicks) {
    }

    /** Notified of every line said and every record closed — the debug journal's hook in. */
    public interface Listener {
        void said(Encounter e, Utterance u);

        void closed(Encounter e);

        Listener NONE = new Listener() {
            @Override
            public void said(Encounter e, Utterance u) {
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
    public Encounter join(BeingId other, Opening opening) {
        long tick = now.getAsLong();
        Encounter e = roster.join(self, other.asPerson(), tick);
        if (e.transcript().isEmpty() && opening != Opening.QUIET) {
            // Either party may open first; the record reads the same both ways — credited to
            // whoever actually hailed, never to whichever body's engine happened to call join().
            AgentId hailer = opening == Opening.I_HAILED ? self : other.asPerson();
            Utterance hail = new Utterance(hailer, SpeechActs.HAIL.key(), Map.of(), tick);
            e.append(hail);
            listener.said(e, hail);
        }
        return e;
    }

    @Override
    public void say(Encounter e, Chooser.Line line) {
        Utterance u = new Utterance(self, line.act().key(), line.payload(), now.getAsLong());
        e.append(u);
        listener.said(e, u);
        if (line.act().ends()) {
            close(e);
        }
    }

    @Override
    public void system(Encounter e, SpeechAct act, AgentId subject) {
        if (e.hasSystem(act.key(), subject)) {
            return;
        }
        Utterance u = Utterance.system(act.key(), subject, now.getAsLong());
        e.append(u);
        listener.said(e, u);
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
        return Picker.maySpeak(e, self, now.getAsLong());
    }

    @Override
    public Optional<AgentId> expiredObligation(Encounter e) {
        return Picker.expiredObligation(e, self, now.getAsLong(), caps.get().patienceTicks());
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
        return new Chooser.Turn(e, applicable, pending, awaiting, greeted(e), counterpart);
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

    private boolean greeted(Encounter e) {
        for (Utterance u : e.transcript()) {
            if (!u.system() && self.equals(u.author()) && u.act().equals(SpeechActs.GREETING.key())) {
                return true;
            }
        }
        return false;
    }
}
