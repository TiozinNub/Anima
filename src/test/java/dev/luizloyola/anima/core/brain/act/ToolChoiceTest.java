package dev.luizloyola.anima.core.brain.act;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.act.ToolChoice.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wield policy, headless: candidates arrive pre-measured (that half is the mod layer's), so
 * every rule (harvest over speed, strictly-faster-than-fist, ties keep the hand) is checkable
 * with plain numbers. Speeds are vanilla's (axe on log 2.0, bare hand 1.0), though nothing here
 * depends on that.
 */
class ToolChoiceTest {

    private static final float BARE = 1.0F;
    private static final int HELD = 0; // hotbar slot 0 unless a test says otherwise

    @Test
    void axeInThePackWinsTheLog() {
        List<Candidate> pack = List.of(
                new Candidate(3, 1.0F, false),   // a stack of food
                new Candidate(12, 2.0F, false)); // the axe, in the backpack
        assertEquals(12, ToolChoice.choose(pack, HELD, BARE, false));
    }

    @Test
    void axeTiesTheFistOnDirtSoTheHandGoesBare() {
        // The dirt lemma: an axe measures 1.0 on dirt, exactly a fist — wearing it would buy
        // nothing. No rule about dirt exists; the numbers alone decide.
        List<Candidate> pack = List.of(new Candidate(12, 1.0F, false));
        assertEquals(ToolChoice.BARE_HAND, ToolChoice.choose(pack, HELD, BARE, false));
    }

    @Test
    void emptyPackGoesBareHanded() {
        assertEquals(ToolChoice.BARE_HAND, ToolChoice.choose(List.of(), HELD, BARE, false));
    }

    @Test
    void heldAxeStaysHeldOnTheNextLog() {
        List<Candidate> pack = List.of(new Candidate(HELD, 2.0F, false));
        assertEquals(ToolChoice.KEEP_HAND, ToolChoice.choose(pack, HELD, BARE, false));
    }

    @Test
    void twoEqualAxesDoNotChurnTheHand() {
        List<Candidate> pack = List.of(
                new Candidate(HELD, 2.0F, false),
                new Candidate(12, 2.0F, false)); // an identical spare in the backpack
        assertEquals(ToolChoice.KEEP_HAND, ToolChoice.choose(pack, HELD, BARE, false));
    }

    @Test
    void equalStrangersPickTheLowerSlotSoTheAnswerIsStable() {
        List<Candidate> pack = List.of(
                new Candidate(14, 2.0F, false),
                new Candidate(9, 2.0F, false));
        assertEquals(9, ToolChoice.choose(pack, HELD, BARE, false));
    }

    @Test
    void harvestBeatsSpeedWhenTheBlockOnlyDropsForTheCorrectTool() {
        // Stone: the correct tool is the slower one here.
        List<Candidate> pack = List.of(
                new Candidate(5, 4.0F, false),  // swift, wrong — a break that drops nothing
                new Candidate(12, 2.0F, true)); // slow, correct — cobble in the pack after
        assertEquals(12, ToolChoice.choose(pack, HELD, BARE, true));
    }

    @Test
    void fastestCorrectToolWinsAmongSeveral() {
        List<Candidate> pack = List.of(
                new Candidate(5, 2.0F, true),   // wooden pick
                new Candidate(12, 6.0F, true)); // iron pick
        assertEquals(12, ToolChoice.choose(pack, HELD, BARE, true));
    }

    @Test
    void nothingCorrectFallsBackToSpeedAlone() {
        // The break was still asked for; drops are lost either way, so dig faster.
        List<Candidate> pack = List.of(new Candidate(12, 2.0F, false));
        assertEquals(12, ToolChoice.choose(pack, HELD, BARE, true));
    }

    @Test
    void heldToolThatOnlyTiesTheFistIsStowed() {
        // Axe still in hand from chopping, now digging dirt: keeping it would cost a durability
        // point per block.
        List<Candidate> pack = List.of(
                new Candidate(HELD, 1.0F, false), // the axe, measured against dirt
                new Candidate(3, 1.0F, false));   // food
        assertEquals(ToolChoice.BARE_HAND, ToolChoice.choose(pack, HELD, BARE, false));
    }

    @Test
    void shovelStillWinsTheDirt() {
        // The dirt lemma spares tools that do not help; one that does help is still wielded.
        List<Candidate> pack = List.of(
                new Candidate(HELD, 1.0F, false), // the axe, useless here
                new Candidate(12, 5.5F, false));  // the shovel
        assertEquals(12, ToolChoice.choose(pack, HELD, BARE, false));
    }
}
