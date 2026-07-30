package dev.luizloyola.anima.core.brain.board;

import dev.luizloyola.anima.core.agent.AgentId;

/**
 * One live hold on a work item, as an operator sees it.
 *
 * <p>A reporting shape, not the record itself: the authoritative lease lives in whatever board
 * minted it. Anima defines it because Anima owns the command, and it carries nothing that would
 * make a consumer's board structure Anima's business.
 *
 * @param who       the holder
 * @param board     which board it was taken from, in that board's own words ({@code "personal"})
 * @param item      the errand's one-line description
 * @param remaining ticks left before the hold lapses if nobody heartbeats it again
 */
public record WorkLease(AgentId who, String board, String item, long remaining) {
}
