package dev.luizloyola.anima.core.brain.sense;

import dev.luizloyola.anima.core.brain.knowledge.Region;

/**
 * One dropped item currently in sight — a bare sighting, the same shape as a threat reading.
 * Supplied by the mod from budgeted entity queries ({@link Percepts#drops()}); whether a drop is
 * grounded or stranded is the consumer's question, answered through {@link Percepts#blocks()}.
 *
 * @param pos    the cell the item's centre falls in — what a walker steers at
 * @param itemId the core inventory's item vocabulary
 * @param box    every cell the item's collision box touches. An item is a quarter of a block wide
 *     and settles anywhere, so near an edge its box straddles two cells (or four) and {@link #pos}
 *     names only one of them — not necessarily the one holding it up. Ask this box what supports a
 *     drop, never the single cell: a log on the lip of a leaf reads as unsupported-and-fetchable
 *     through {@code pos} alone, which is the bait a crowd of gatherers walks at forever.
 */
public record Drop(Pos pos, String itemId, Region box) {
}
