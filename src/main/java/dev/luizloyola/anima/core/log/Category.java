package dev.luizloyola.anima.core.log;

/**
 * The subsystem a {@link Entry journal entry} came from — the third column of a log line
 * ({@code Bob - <category> - event - detail}). A closed enum, not a free string, so the debug
 * command and the file sink can filter and colour without parsing.
 *
 * <ul>
 *   <li>{@link #BRAIN} — decisions: arbitration, task trees starting and finishing, autonomy.</li>
 *   <li>{@link #PATHFIND} — movement: routes requested, accepted or failed, recalculates, strays.
 *       </li>
 *   <li>{@link #BODY} — what the body suffers: damage, starvation, death, eating.</li>
 *   <li>{@link #SENSE} — perception: a POI noticed into or forgotten from the knowledge store.</li>
 *   <li>{@link #PROJECT} — the work lifecycle in two voices: the board (posted / closed /
 *       cooldown) and the arbiter's commitments (claimed / started / suspended / resumed /
 *       completed / failed).</li>
 * </ul>
 *
 * <p>An entry-free layer 3/4 logs through the same service against its {@code AgentId}, so the
 * enum, not the emitter's location, decides the column.
 */
public enum Category {
    BRAIN,
    PATHFIND,
    BODY,
    SENSE,
    PROJECT
}
