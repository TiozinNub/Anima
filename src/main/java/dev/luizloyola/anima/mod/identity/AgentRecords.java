package dev.luizloyola.anima.mod.identity;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.MinecraftServer;

/**
 * Every store that keeps something under an {@link AgentId}, asked in one call to let that agent
 * go.
 *
 * <p><b>A registry, not a method that lists the stores.</b> The old {@code purge graveyard} command
 * knew three of the four stores that existed and left 722 orphan party rows in the dev world;
 * registration lives beside each store instead, so a store that forgets to register also forgot to
 * persist.
 *
 * <p><b>Erasure is not death.</b> Erasure unmakes an agent removed by command; a burial wipes only
 * what nothing can read again and keeps the identity, because the dead stay named in the contact
 * books of everyone who knew them. Both go through this seam, differing in which erasers run.
 *
 * <p>Server thread only, like the stores it reaches.
 */
public final class AgentRecords {

    private AgentRecords() {
    }

    /** What one store does when an agent is let go. */
    @FunctionalInterface
    public interface Eraser {
        /**
         * Drop everything this store holds for {@code who}.
         *
         * @return whether anything was actually there — so a readout can name the stores that had
         *         something rather than listing every store every time
         */
        boolean erase(MinecraftServer server, AgentId who);
    }

    private record Registered(String store, Eraser eraser, boolean survivesDeath) {
    }

    private static final List<Registered> ERASERS = new CopyOnWriteArrayList<>();

    /**
     * Registers one store's eraser. Call during mod initialization, beside that store's other
     * wiring.
     *
     * @param store         the store's name for readouts, e.g. {@code "knowledge"}
     * @param survivesDeath whether a BURIAL leaves this store alone. {@code true} for anything a
     *                      living agent can still read about the dead (an identity, a name), and
     *                      {@code false} for anything only its owner could ever have read. An
     *                      erasure ignores this and runs everything.
     */
    public static void register(String store, boolean survivesDeath, Eraser eraser) {
        ERASERS.add(new Registered(store, eraser, survivesDeath));
    }

    /**
     * Unmakes {@code who} everywhere: every registered store drops what it holds. For a Person
     * removed by command, not for one who died — see {@link #bury}.
     *
     * @return the names of the stores that actually held something, in registration order
     */
    public static List<String> erase(MinecraftServer server, AgentId who) {
        return run(server, who, false);
    }

    /**
     * Wipes only what nothing can read again, keeping whatever survives death. The narrower half
     * of {@link #erase}, and the one a death runs.
     *
     * @return the names of the stores that actually held something, in registration order
     */
    public static List<String> bury(MinecraftServer server, AgentId who) {
        return run(server, who, true);
    }

    private static List<String> run(MinecraftServer server, AgentId who, boolean burial) {
        List<String> touched = new ArrayList<>();
        for (Registered registered : ERASERS) {
            if (burial && registered.survivesDeath()) {
                continue;
            }
            if (registered.eraser().erase(server, who)) {
                touched.add(registered.store());
            }
        }
        return touched;
    }

    /** Every registered store's name, in registration order — for a readout that wants the map. */
    public static List<String> stores() {
        return ERASERS.stream().map(Registered::store).toList();
    }
}
