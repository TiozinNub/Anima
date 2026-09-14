package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import java.util.Objects;
import java.util.Optional;

/**
 * One consumer slot for the chooser's personality, plus Anima's own fallback — Anima has no
 * personality of its own, so a mod that never calls {@link #provide} still gets a body that
 * greets once and leaves when it is no longer wanted.
 */
public final class Choosers {

    /**
     * Greets once, answers a constrained pending ask with its first declared response — which is
     * how it acknowledges a goodbye — and says goodbye itself once company pressure reads
     * {@code 0.0} and nobody has spoken for its patience; otherwise silence, because proximity
     * alone is still company. Never initiates while {@link Chooser.Turn#awaiting()} is present:
     * an ask already on the table doesn't get asked twice.
     */
    public static final Chooser BASIC = (ctx, turn) -> {
        Optional<Chooser.Line> reply = respondToPending(turn);
        if (reply.isPresent()) {
            return reply.get();
        }
        if (turn.awaiting().isPresent()) {
            return null;   // already asked something of the other party — wait for their answer
        }
        if (!turn.greeted() && turn.applicable().contains(SpeechActs.GREETING)) {
            return Chooser.Line.of(SpeechActs.GREETING);
        }
        if (ctx.percepts().needs().pressure(NeedKind.COMPANY) == 0.0
                && turn.applicable().contains(SpeechActs.END_CHAT)
                && Picker.silence(turn.encounter(), ctx.percepts().time())
                        > ctx.profile().i(ProfileAspect.SOCIAL_PATIENCE_TICKS)) {
            return Chooser.Line.of(SpeechActs.END_CHAT);
        }
        return null;
    };

    /** Falls back to {@link #BASIC} until a consumer calls {@link #provide}. */
    private static Chooser current = BASIC;

    private Choosers() {
    }

    /** Registers the chooser every {@link Speech} port defers to from now on — last one wins. */
    public static void provide(Chooser chooser) {
        current = Objects.requireNonNull(chooser, "chooser");
    }

    /** The registered chooser, or {@link #BASIC} when nobody has provided one. */
    public static Chooser get() {
        return current;
    }

    /**
     * The pending ask's first declared response, if it has one and it survived the engine's own
     * applicability filter (an act may be a declared response and still be off the table — say,
     * an ending response asked before anybody proposed ending).
     */
    private static Optional<Chooser.Line> respondToPending(Chooser.Turn turn) {
        return turn.pending()
                .flatMap(u -> SpeechActs.byKey(u.act()))
                .map(SpeechAct::responses)
                .filter(responses -> !responses.isEmpty())
                .flatMap(responses -> SpeechActs.byKey(responses.get(0)))
                .filter(act -> turn.applicable().contains(act))
                .map(Chooser.Line::of);
    }
}
