package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * How much company this body has had lately — the first need that is genuinely its own number.
 *
 * <p><b>A band, not a floor.</b> Hunger only ever runs out; company runs out at both ends — below
 * the band a body wants company, above it a body wants to be left alone, inside it nothing. Hermits
 * and extroverts are then the same mechanism at different band centres, and it is why
 * {@link Gauge#pressure()} is separate from {@link Gauge#level()}: 0.05 and 0.95 are equally
 * uncomfortable and could never be one number.
 *
 * <p><b>Both proximity and real conversation move it</b> (decision: Luiz). Solitude drains it
 * whatever else is happening, so a crowd is a net gain and being alone a net loss, with no branch
 * anywhere saying so.
 *
 * <p><b>A conversation is worth what was SAID in it, not how long it stayed open</b> (decision:
 * Luiz, 2026-08-06). Filling per open-encounter tick priced silence — a slow replier made better
 * company than a brisk one — so the fill is {@link #conversed()}, one event per utterance
 * exchanged; time together counts through {@link #observe(int)}.
 *
 * <p><b>Known people, not any people.</b> {@link #observe(int)} counts only people this body has
 * met, so a stranger leaves the drive to go and meet them intact rather than satisfying it by
 * sight. A player counts exactly like a Person, by not being special-cased.
 *
 * <p><b>Every number is read live from the profile</b>, so a species retuned at runtime retunes the
 * bodies already walking around. It arrives as a supplier because this gauge is built in a field
 * initializer, before a body can say what species it is.
 */
public final class Company implements Gauge {

    /** Where a level sits relative to the comfort band — the reading a drive branches on. */
    public enum Band {
        LONELY,
        CONTENT,
        CROWDED
    }

    private final Supplier<AgentProfile> profile;

    /**
     * {@code 0..1}, or unseeded — a fresh body starts CONTENT, at the centre of its own band, and
     * its band is a species question it cannot answer during construction. Loading a saved level
     * seeds it too, so a body restored from disk never passes through the default.
     */
    private double level;
    private boolean seeded;

    /** Pushed by the body each tick: how many people it can currently see or hear and has met. */
    private int nearby;

    public Company(Supplier<AgentProfile> profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public NeedKind kind() {
        return NeedKind.COMPANY;
    }

    /** How many met people are within sight or earshot right now; the body recounts every tick. */
    public void observe(int knownNearby) {
        this.nearby = Math.max(0, knownNearby);
    }

    /**
     * One line was exchanged in a conversation this body is part of — said by it or to it. An
     * EVENT, not a state: it lands in full, and nothing has to be told when a conversation ends.
     *
     * <p><b>Call this as an utterance ARRIVES, never by walking a transcript.</b> Encounters persist
     * and resume, so a body that counted the lines it could see would come back from a restart
     * having had the same chat twice.
     */
    public void conversed() {
        AgentProfile p = seeded();
        level = clamp(level + perStep(p.i(ProfileAspect.SOCIAL_COMPANY_UTTERANCES)));
    }

    /**
     * One tick of company: solitude drains and every known person nearby trickles in. The two are
     * summed, so "alone" and "in a crowd" are not states this has to know about.
     *
     * <p>Conversation is absent, arriving through {@link #conversed()} when something
     * is actually said.
     */
    @Override
    public void tick() {
        AgentProfile p = seeded();
        double delta = nearby * perStep(p.i(ProfileAspect.SOCIAL_COMPANY_PROXIMITY_TICKS))
                - perStep(p.i(ProfileAspect.SOCIAL_COMPANY_SOLITUDE_TICKS));
        level = clamp(level + delta);
    }

    @Override
    public double level() {
        seeded();
        return level;
    }

    /**
     * How far outside the band, normalized against the room on that side — so a body at 0 with a
     * band starting at 0.35 is at full pressure, and so is a body at 1 with a band ending at 0.85.
     * Inside the band there is nothing to want and this is 0.
     */
    @Override
    public double pressure() {
        AgentProfile p = seeded();
        double low = low(p);
        double high = high(p);
        if (level < low) {
            return low <= 0.0 ? 0.0 : (low - level) / low;
        }
        if (level > high) {
            return high >= 1.0 ? 0.0 : (level - high) / (1.0 - high);
        }
        return 0.0;
    }

    public Band band() {
        AgentProfile p = seeded();
        if (level < low(p)) {
            return Band.LONELY;
        }
        return level > high(p) ? Band.CROWDED : Band.CONTENT;
    }

    /** Sets the level directly — the load path, and the dev command that stages a mood. */
    public void setLevel(double value) {
        this.level = clamp(value);
        this.seeded = true;
    }

    @Override
    public String describe() {
        AgentProfile p = seeded();
        return String.format(Locale.ROOT, "company %.2f in [%.2f, %.2f] (%s)%s",
                level, low(p), high(p), band().name().toLowerCase(Locale.ROOT),
                nearby > 0 ? " with " + nearby : "");
    }

    /**
     * The profile, having first put the level at the band's centre if nothing has yet. Every read
     * goes through here, so there is no order in which a caller can see the unseeded 0 — which
     * would have read as a body born desperately lonely.
     */
    private AgentProfile seeded() {
        AgentProfile p = profile.get();
        if (!seeded) {
            level = clamp(p.d(ProfileAspect.SOCIAL_COMPANY_CENTER));
            seeded = true;
        }
        return p;
    }

    private static double low(AgentProfile p) {
        return Math.max(0.0, p.d(ProfileAspect.SOCIAL_COMPANY_CENTER)
                - p.d(ProfileAspect.SOCIAL_COMPANY_WIDTH) / 2.0);
    }

    private static double high(AgentProfile p) {
        return Math.min(1.0, p.d(ProfileAspect.SOCIAL_COMPANY_CENTER)
                + p.d(ProfileAspect.SOCIAL_COMPANY_WIDTH) / 2.0);
    }

    /**
     * A "how many of these cross the whole gauge" aspect as one step's worth; 0 means no effect.
     * Serves both kinds of step this gauge has — a tick of the clock and a line of conversation.
     */
    private static double perStep(int steps) {
        return steps <= 0 ? 0.0 : 1.0 / steps;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
