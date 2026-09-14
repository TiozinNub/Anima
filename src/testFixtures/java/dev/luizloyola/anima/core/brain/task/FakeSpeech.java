package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Choosers;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Encounters;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechEngine;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Test double for the {@link Speech} port. A real {@link SpeechEngine} underneath (pure and
 * headless anyway) over settable {@link #caps} and {@link #chooser}, recording every line said
 * and every record closed so a task test can assert on the transcript without reaching into
 * {@link #roster}.
 *
 * <p>{@code SpeechEngine} cannot be extended (it is final by design — see its own doc), so this
 * wraps one instead of being one.
 */
public final class FakeSpeech implements Speech {
    /** The world this body's encounters live in — share one across two contexts for a two-party test. */
    public final Encounters roster;
    public final List<Utterance> saidLines = new ArrayList<>();
    public final List<Encounter> closedRecords = new ArrayList<>();
    /** Mutable so a test can shrink the turn cap, the tick cap, the stale gap or patience. */
    public SpeechEngine.Caps caps = new SpeechEngine.Caps(60, 6_000, 1_200, 300);
    /** Swappable so a test can pin a scripted chooser without touching the static {@link Choosers} slot. */
    public Chooser chooser = Choosers.BASIC;
    private final SpeechEngine engine;

    /** A fresh, private roster — the common case: one body, its own conversations. */
    public FakeSpeech(AgentId self, LongSupplier now) {
        this(self, now, new Encounters());
    }

    /** Over {@code shared}, so two {@code FakeContext}s can meet in the same encounter. */
    public FakeSpeech(AgentId self, LongSupplier now, Encounters shared) {
        this.roster = shared;
        this.engine = new SpeechEngine(self, now, roster, () -> chooser, () -> caps,
                new SpeechEngine.Listener() {
                    @Override
                    public void said(Encounter e, Utterance u) {
                        saidLines.add(u);
                    }

                    @Override
                    public void closed(Encounter e) {
                        closedRecords.add(e);
                    }
                });
    }

    @Override
    public Optional<Encounter> current() {
        return engine.current();
    }

    @Override
    public Optional<Encounter> join(BeingId other, Opening opening) {
        return engine.join(other, opening);
    }

    @Override
    public void say(Encounter e, Chooser.Line line) {
        engine.say(e, line);
    }

    @Override
    public void system(Encounter e, SpeechAct act, AgentId subject) {
        engine.system(e, act, subject);
    }

    @Override
    public void close(Encounter e) {
        engine.close(e);
    }

    @Override
    public boolean maySpeak(Encounter e) {
        return engine.maySpeak(e);
    }

    @Override
    public Optional<AgentId> expiredObligation(Encounter e) {
        return engine.expiredObligation(e);
    }

    @Override
    public boolean lapsedFarewell(Encounter e) {
        return engine.lapsedFarewell(e);
    }

    @Override
    public Chooser.Turn turn(Encounter e) {
        return engine.turn(e);
    }

    @Override
    public Optional<AgentId> counterpart(Encounter e) {
        return engine.counterpart(e);
    }

    @Override
    public Chooser chooser() {
        return engine.chooser();
    }
}
