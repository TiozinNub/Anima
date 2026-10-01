package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.Objects;

/**
 * Something the world is doing for a party at one of its places, set going by a member — a
 * furnace smelting (directions spec, decision 21). Remembered so that somebody comes back when it
 * should be done, rather than standing by or waiting for a passer-by to look.
 *
 * @param what the kind of work, {@code "smelt"} today
 * @param input what went in, and how many are still to be worked
 * @param output what should come out
 * @param starter who set it going — offered the follow-up first (decision 22)
 * @param dueAt game time it should be done, predicted when it started; the follow-up reads the
 *     place itself, since fuel can run out and a player can empty it
 */
public record Process(String what, String input, int inputCount, String output, AgentId starter,
                      long startedAt, long dueAt) {

    public Process {
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(starter, "starter");
    }

    public boolean due(long now) {
        return now >= dueAt;
    }
}
