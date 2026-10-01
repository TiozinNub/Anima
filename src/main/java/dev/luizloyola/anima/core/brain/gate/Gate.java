package dev.luizloyola.anima.core.brain.gate;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.log.Category;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What a body may seek, make and do. <b>Anima asks and never names why</b>: a consumer installs a
 * {@link Policy}, and with none installed every answer is yes.
 *
 * <p>Seeking and making are gated; using and keeping are not. {@code ToolChoice} and the standing
 * wants never ask, so a body handed an item it could not have made still wields it and keeps it.
 *
 * <p>Asked through a {@link View}, which binds the body — the {@code Board.viewFor} pattern — so no
 * task carries identity.
 */
public final class Gate {

    /** The consumer's answers. Empty is yes; a refusal is why not, worded for the journal. */
    public interface Policy {
        Optional<String> refuseItem(AgentId body, String itemId);

        Optional<String> refuseAct(AgentId body, Act act);
    }

    /** Every answer yes — what runs until a consumer installs its own. */
    public static final Policy OPEN = new Policy() {
        @Override
        public Optional<String> refuseItem(AgentId body, String itemId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> refuseAct(AgentId body, Act act) {
            return Optional.empty();
        }
    };

    private static volatile Policy policy = OPEN;

    /**
     * Bumped whenever an answer may have changed. Each view says a refusal once, and a refusal that
     * came back after a grant and a revoke is news again.
     */
    private static volatile int epoch;

    private Gate() {
    }

    public static void install(Policy installed) {
        policy = Objects.requireNonNull(installed, "policy");
        changed();
    }

    /** The consumer's answers may have changed — a reload, a grant, a node reached. */
    public static void changed() {
        epoch++;
    }

    public static View viewFor(AgentId body, AgentJournal journal) {
        return new View(body, journal);
    }

    /**
     * One body's questions. Every refusal writes a journal line, <b>once per item or act and
     * reason</b>: a gated settler otherwise reads as a broken one, and one asked every tick would
     * bury everything else it wrote.
     */
    public static final class View {

        /** The view of a rig with no body behind it — every answer yes, nothing written. */
        public static final View OPEN = new View(null, null);

        private final @Nullable AgentId body;
        private final @Nullable AgentJournal journal;
        private final Set<String> said = new HashSet<>();
        private int saidAt = -1;

        private View(@Nullable AgentId body, @Nullable AgentJournal journal) {
            this.body = body;
            this.journal = journal;
        }

        /** Whether this body may make the item — a recipe's output. */
        public boolean mayMake(String itemId) {
            if (body == null) {
                return true;
            }
            Optional<String> why = policy.refuseItem(body, itemId);
            why.ifPresent(reason -> say("won't make " + itemId, reason));
            return why.isEmpty();
        }

        /**
         * {@link #mayMake} without a journal line: for a body weighing what it could make, not
         * trying to make it.
         */
        public boolean wouldMake(String itemId) {
            return body == null || policy.refuseItem(body, itemId).isEmpty();
        }

        /**
         * Whether this body may go after anything {@code spec} names. Only a literal spec can be
         * judged — a mod-declared one is a predicate with nothing to enumerate — and it is refused
         * only when every item it names is: "any pickaxe" stays open while a wooden one can be made.
         */
        public boolean maySeek(ItemSpec spec) {
            if (body == null) {
                return true;
            }
            Optional<Set<String>> ids = ItemSpec.literalIds(spec);
            if (ids.isEmpty()) {
                return true;
            }
            // Sorted so the reason quoted for a family is the same one on every run.
            String reason = null;
            for (String id : new TreeSet<>(ids.get())) {
                Optional<String> why = policy.refuseItem(body, id);
                if (why.isEmpty()) {
                    return true;
                }
                if (reason == null) {
                    reason = why.get();
                }
            }
            say("won't seek " + spec.name(), reason);
            return false;
        }

        public boolean mayDo(Act act) {
            if (body == null) {
                return true;
            }
            Optional<String> why = policy.refuseAct(body, act);
            why.ifPresent(reason -> say("won't " + act.key(), reason));
            return why.isEmpty();
        }

        private void say(String what, String reason) {
            int now = epoch;
            if (saidAt != now) {
                said.clear();
                saidAt = now;
            }
            String line = what + ": " + reason;
            if (said.add(line)) {
                journal.record(Category.BRAIN, "gate", line);
            }
        }
    }
}
