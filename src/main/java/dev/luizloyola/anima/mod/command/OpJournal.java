package dev.luizloyola.anima.mod.command;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.log.Journals;
import java.util.Collection;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * What the operator did, filed under every agent it touched — the {@code op} channel, called from
 * both command trees so there is one implementation of the routing rule.
 *
 * <p><b>Only the verbs that CHANGE something.</b> There is deliberately no central hook: one would
 * also log every {@code board}, {@code needs} and {@code peers} read, and a single debugging
 * session fires dozens of those. The cost is that a new mutating verb can forget to call this, and
 * its silence looks exactly like nothing having happened
 * ({@code docs/superpowers/specs/2026-08-28-journal-observability-design.md}).
 *
 * <p><b>Duplicated across a party, not filed under a subject.</b> A player has no journal, so a
 * party-wide command is written to every member — each settler's file then explains, on its own,
 * why work appeared, which a subject the command never had could not.
 *
 * <p>The {@code journal.op} knob mutes {@link Category#OP} whole, so nothing here consults it:
 * {@link JournalService#record} drops the line before any sink sees it.
 */
public final class OpJournal {

    private OpJournal() {
    }

    /**
     * One line per touched agent: the operator's name in the event column, what they did in the
     * detail. Entity-free, so it is also the shape a test drives.
     */
    public static void record(JournalService journal, Collection<AgentId> touched,
                              String operator, String detail) {
        for (AgentId who : touched) {
            journal.record(who, Category.OP, operator, detail);
        }
    }

    /** A command about one agent. Silent when nothing resolved — the caller has already said so. */
    public static void record(CommandSourceStack source, @Nullable AgentId who, String detail) {
        if (who == null) {
            return;
        }
        record(source, List.of(who), detail);
    }

    /** A command about a party, or about a pair: every agent it reached hears about it. */
    public static void record(CommandSourceStack source, Collection<AgentId> touched, String detail) {
        MinecraftServer server = source.getServer();
        record(Journals.of(server), AgentDirectory.of(server), touched, source.getTextName(), detail);
    }

    /**
     * The same fan-out, minus only the server lookup — the seam a test drives.
     *
     * <p>A PLAYER's own id arrives here through {@code contacts meet}, {@code party join} and
     * {@code party leave}, where an operator acting as themselves is one of the parties. Filtered
     * here rather than at those call sites: a player has no journal, and minting one would leave a
     * ring and a {@code logs/anima/} file for somebody who never thinks.
     */
    static void record(JournalService journal, AgentDirectory directory, Collection<AgentId> touched,
                       String operator, String detail) {
        List<AgentId> agents = touched.stream()
                .filter(id -> directory.identity(id).isPresent())
                .toList();
        if (agents.isEmpty()) {
            return;
        }
        record(journal, agents, operator, detail);
    }
}
