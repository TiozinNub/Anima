package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.instinct.ConverseInstinct;
import dev.luizloyola.anima.core.brain.instinct.Drives;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Answering a call is its own drive, not company's — a CONTENT body must still be reachable, or
 * nobody can get anyone's attention and the player's right-click has nothing to land on.
 *
 * <p>Where its bid sits is the whole of "ignoring is not a behaviour, it is Converse losing the
 * bid": above the wander floor so an idle body comes, below {@code mind.preempt} so a shout waits
 * for a task boundary instead of cutting into work.
 */
class ConverseInstinctTest {

    private final FakeContext ctx = new FakeContext();
    private final ConverseInstinct converse = new ConverseInstinct();

    @Test
    void aQuietFieldAsksForNothing() {
        assertEquals(0.0, converse.pressure(ctx), "zero pressure is not a bid");
    }

    @Test
    void aCallIsWorthAnsweringEvenWhenNothingIsWrong() {
        ctx.percepts.beings = List.of(FakePercepts.hailingPersonAt(new Pos(30, 64, 0), 30.0));

        double bid = converse.pressure(ctx);
        assertEquals(ctx.profile.d(ProfileAspect.SOCIAL_HAIL_ANSWER_PRESSURE), bid, 1e-9);
        assertTrue(bid > ctx.profile.d(ProfileAspect.WANDER_IDLE_PRESSURE),
                "an idle body comes when called");
        assertTrue(bid < Arbiter.preempt(ctx.profile),
                "but a shout does not cut into work mid-errand — it waits for the boundary");

        // Against the OTHER BIDDER, not against the lonely level's declared pressure: what
        // seek_people actually offers is the ramp's reading, and below the lonely knee that runs
        // up to the axis floor's 1.0 rather than stopping at the knee's 0.50.
        ctx.percepts.company.setValue(insideTheLonelyBand());
        assertTrue(bid > Drives.SEEK_PEOPLE.pressure(ctx),
                "or a lonely body would rather go looking than answer someone already calling it");
    }

    /**
     * Where {@code seek_people}'s bid crosses {@code pressure} on its way up, from the declared
     * numbers rather than a remembered one.
     *
     * <p>{@code COMPANY}'s lowest knee is {@code lonely}, and {@link dev.luizloyola.anima.core
     * .agent.need.Ramp} pins the unanchored end of an axis at full pressure — so the stretch below
     * that knee is a line from {@code (axisMin, 1.0)} to the knee, and every claim the design's
     * threshold ladder makes about "lonely" holds only ABOVE the crossing.
     */
    private double companyWhereSeekingBids(double pressure) {
        NeedLevel lonely = NeedKind.COMPANY.level("lonely").orElseThrow();
        double floor = NeedKind.COMPANY.axisMin();
        double along = (1.0 - pressure) / (1.0 - lonely.pressure(ctx.profile));
        return floor + along * (lonely.value(ctx.profile) - floor);
    }

    /** A lonely body whose loneliness is not yet worse than being called. */
    private double insideTheLonelyBand() {
        double crossing =
                companyWhereSeekingBids(ctx.profile.d(ProfileAspect.SOCIAL_HAIL_ANSWER_PRESSURE));
        return (crossing + NeedKind.COMPANY.level("lonely").orElseThrow().value(ctx.profile)) / 2.0;
    }

    /**
     * The ladder in the voice-and-hail design says answering always beats going looking. It does
     * not: below the crossing worked out here, {@code seek_people} out-bids Converse, and lower
     * still it out-bids {@code mind.preempt} and cuts into work. Recorded rather than retuned —
     * whether an empty body should out-bid everything is a product question, and hunger already
     * behaves that way (see docs/BUGS.md, 2026-08-19).
     */
    @Test
    void anAlmostEmptyBodyWouldRatherGoLookingThanAnswer() {
        double answering = ctx.profile.d(ProfileAspect.SOCIAL_HAIL_ANSWER_PRESSURE);
        double crossing = companyWhereSeekingBids(answering);
        assertEquals(0.1575, crossing, 1e-9,
                "0.175 × (1 − 0.55) ÷ (1 − 0.50) — move a company level and move the ladder too");
        assertEquals(0.14, companyWhereSeekingBids(Arbiter.preempt(ctx.profile)), 1e-9,
                "and below this one a hail cannot even reach the body: seeking preempts");

        ctx.percepts.beings = List.of(FakePercepts.hailingPersonAt(new Pos(30, 64, 0), 30.0));
        ctx.percepts.company.setValue(crossing - 0.005);
        assertTrue(Drives.SEEK_PEOPLE.pressure(ctx) > converse.pressure(ctx),
                "below the crossing the lonely end wins, and the call goes unanswered");

        ctx.percepts.company.setValue(crossing + 0.005);
        assertTrue(converse.pressure(ctx) > Drives.SEEK_PEOPLE.pressure(ctx),
                "above it the ladder reads as written");
    }

    @Test
    void itWalksToWhoeverCalled() {
        ctx.percepts.beings = List.of(FakePercepts.hailingPersonAt(new Pos(30, 64, 0), 30.0));

        var a = converse.root(ctx);
        var b = converse.root(ctx);
        assertInstanceOf(Answer.class, a);
        assertNotSame(a, b, "a fresh tree every grant — never a cached instance");
    }

    @Test
    void itWillNotCrossTheWorldForAShout() {
        ctx.percepts.beings = List.of(FakePercepts.hailingPersonAt(new Pos(30, 64, 0), 30.0));
        assertEquals(2.0 * ctx.profile.i(ProfileAspect.SOCIAL_HAIL_RADIUS),
                converse.costTolerance(ctx), 1e-9,
                "a hail is within earshot by definition; twice that covers a detour and no more");
    }
}
