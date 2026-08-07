package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * How much company this body has had lately — the first need that is genuinely its own number.
 *
 * <p><b>Comfortable in the middle, unhappy at both ends.</b> Hunger only ever runs out; company
 * runs out at both. That shape is four ordinary {@link NeedKind#COMPANY} levels — desolate, alone,
 * content, crowded — whose pressures dip to nothing and rise again; neither end of the axis is
 * anchored by a level, so both pin at full pressure and the V falls out of the same {@link Ramp}
 * hunger uses. Hermits and extroverts are that ramp with the corners moved.
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

    private final Supplier<AgentProfile> profile;

    /**
     * {@code 0..1}, or unseeded — a fresh body starts in the middle of what its species finds
     * comfortable, and that is a species question it cannot answer during construction. Loading a
     * saved value seeds it too, so a body restored from disk never passes through the default.
     */
    private double value;
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
        value = clamp(value + perStep(p.i(ProfileAspect.SOCIAL_COMPANY_UTTERANCES)));
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
        value = clamp(value + delta);
    }

    @Override
    public double value() {
        seeded();
        return value;
    }

    @Override
    public double pressure() {
        return NeedKind.COMPANY.ramp().pressureAt(seeded(), value);
    }

    @Override
    public NeedLevel level() {
        return NeedKind.COMPANY.ramp().levelAt(seeded(), value);
    }

    /** Sets the value directly — the load path, and the dev command that stages a mood. */
    public void setValue(double level) {
        this.value = clamp(level);
        this.seeded = true;
    }

    @Override
    public String describe() {
        AgentProfile p = seeded();
        return String.format(Locale.ROOT, "company %.2f (%s)%s",
                value, NeedKind.COMPANY.ramp().levelAt(p, value).key(),
                nearby > 0 ? " with " + nearby : "");
    }

    /**
     * The profile, having first seeded the value into the middle of this species' comfortable
     * stretch. Every read goes through here, so no caller can see the unseeded 0 — a body born
     * desperately lonely.
     *
     * <p>Comfortable means "no pressure", so the seed is the midpoint of the levels that ask for
     * nothing: a species whose comfortable stretch moves takes its newborns with it.
     */
    private AgentProfile seeded() {
        AgentProfile p = profile.get();
        if (!seeded) {
            double low = Double.NaN;
            double high = Double.NaN;
            for (NeedLevel level : NeedKind.COMPANY.levels()) {
                if (level.pressure(p) <= 0.0) {
                    double at = level.value(p);
                    low = Double.isNaN(low) ? at : Math.min(low, at);
                    high = Double.isNaN(high) ? at : Math.max(high, at);
                }
            }
            value = Double.isNaN(low) ? clamp(NeedKind.COMPANY.axisMax() / 2.0)
                    : clamp((low + high) / 2.0);
            seeded = true;
        }
        return p;
    }

    /** A "how many of these cross the whole gauge" aspect as one step's worth; 0 means no effect. */
    private static double perStep(int steps) {
        return steps <= 0 ? 0.0 : 1.0 / steps;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
