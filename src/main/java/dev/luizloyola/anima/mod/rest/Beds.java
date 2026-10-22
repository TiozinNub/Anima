package dev.luizloyola.anima.mod.rest;

import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Which beds a body may hold, said by whoever knows what a bed means to it — Anima knows none, so
 * with nothing registered no body takes a bed. Every registered rule must agree, and is asked again
 * whenever a held bed is used, so a bed a body may no longer keep is let go.
 */
public final class Beds {

    @FunctionalInterface
    public interface Rule {
        /** Whether {@code body} may hold the bed whose head is {@code head}. */
        boolean mayHold(ServerLevel level, BlockPos head, AgentBody body);
    }

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();

    private Beds() {
    }

    public static void register(Rule rule) {
        RULES.add(rule);
    }

    static boolean mayHold(ServerLevel level, BlockPos head, AgentBody body) {
        if (RULES.isEmpty()) {
            return false;
        }
        for (Rule rule : RULES) {
            if (!rule.mayHold(level, head, body)) {
                return false;
            }
        }
        return true;
    }
}
