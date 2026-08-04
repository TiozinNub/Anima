package dev.luizloyola.anima.mod.identity;

import dev.luizloyola.anima.mod.brain.KnowledgeData;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.social.ContactData;
import dev.luizloyola.anima.mod.social.PartyData;

/**
 * Anima's own {@link AgentRecords} registrations — everything the library keeps under an
 * {@link dev.luizloyola.anima.core.agent.AgentId}, and what each does when its agent is let go.
 * The {@code survivesDeath} column is the burial policy:
 *
 * <ul>
 *   <li><b>knowledge</b> — wiped by a death: only the mind that made it ever asks for it, and it
 *       is the largest per-agent store by an order of magnitude.
 *   <li><b>parties</b> — wiped, party of one included: a stale member is a wrong answer, since a
 *       board would wait on errands nobody will ever run.
 *   <li><b>contacts</b> — <b>survives</b>: "I knew Alice" stays true after Alice dies; a listing
 *       filters the dead out at read time.
 *   <li><b>journal</b> — <b>survives</b>: the death is the most interesting line the ring holds,
 *       and the durable file is untouched either way.
 * </ul>
 */
public final class AnimaRecords {

    private AnimaRecords() {
    }

    /** Call once from mod init. */
    public static void install() {
        AgentRecords.register("knowledge", false,
                (server, who) -> KnowledgeData.get(server).forget(who));
        AgentRecords.register("parties", false,
                (server, who) -> PartyData.get(server).evict(who));
        AgentRecords.register("contacts", true,
                (server, who) -> ContactData.get(server).erase(who));
        AgentRecords.register("journal", true,
                (server, who) -> Journals.of(server).drop(who));
    }
}
