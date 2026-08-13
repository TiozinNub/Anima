package dev.luizloyola.anima.mod.identity;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * What happens to a mind when its body dies: the death is written down, and everything nothing can
 * read again is let go.
 *
 * <p>Here or nowhere — afterwards a directory entry with no loaded body looks exactly like one in
 * an unloaded chunk. Hence {@code die()} rather than removal: a body lingers through its death
 * animation, and a server stopped in that window must not lose the fact. {@link Graves#bury} is
 * idempotent, so the lingering costs nothing.
 *
 * <p>It writes only what {@link AgentBody} already carries, so it is library code rather
 * than the body-owning mod's. Inventory is deliberately not kept (decision: Luiz): those items lie
 * where they fell, and a second copy would drift.
 *
 * <p>Server thread only.
 */
public final class Burial {

    private Burial() {
    }

    /**
     * How many of the dying agent's last journal lines are copied into its grave.
     *
     * <p>Read through the store on use, not cached, so {@code config reload} retunes it.
     */
    public static int tailEntries() {
        return Config.get().i(Knob.JOURNAL_DEATH_TAIL);
    }

    /**
     * Writes {@code body}'s death down and lets go of everything a dead mind cannot use.
     *
     * <p>Let go: knowledge (asked for by that mind alone) and party membership (boards count
     * members, and a dead one makes a party larger than it is). Identity, the contact books naming
     * them, their journal and the grave all stay.
     *
     * <p>Nothing goes to chat — a death reaches the server log, the agent's journal and the grave;
     * who gets told is the social layer's question, answered from the grave.
     *
     * @param body  the one who died, its entity still present and still standing where it fell
     * @param cause what killed it, as the body's own {@code die()} was handed it
     */
    public static void record(AgentBody body, DamageSource cause) {
        LivingEntity entity = body.entity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        // The sentence already names them ("Alice was slain by Zombie"), so it is logged as-is.
        //
        // Asked of the DAMAGE, not the combat tracker: an agent's tracker entry list is empty by
        // `die()`, so `getDeathMessage` falls back to the bare `death.attack.generic`. The damage
        // source is what the tracker delegates to and cannot be empty.
        String story = cause.getLocalizedDeathMessage(entity).getString();
        AnimaMod.LOGGER.info("{}", story);
        body.journal().record(Category.BODY, "death", story);

        AgentId id = body.agentId();
        if (id == null) {
            return; // died before anybody decided who they were; there is nothing to bury
        }
        MinecraftServer server = level.getServer();
        // The tail is read after the death line is filed, so a grave's last word is the death
        // itself, and before the erasers run, so no store this later reaches can empty it first.
        boolean news = Graves.get(server).bury(id, new Graves.Death(
                level.getGameTime(),
                level.dimension().identifier().toString(),
                entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
                story,
                cause.type().msgId(),
                killerName(cause),
                killerId(cause),
                mindAtTheEnd(body, level.getGameTime()),
                body.journal().recent(tailEntries())));
        if (news) {
            AgentRecords.bury(server, id);
        }
    }

    /**
     * The state of the mind as it ended, one {@code label: sentence} per line.
     *
     * <p>Borrowed from what {@code brain}, {@code nav}, the setback readout and {@code needs} print
     * about a living body rather than re-derived, so the two cannot drift and a better
     * {@code describe()} improves every grave written afterwards.
     */
    private static List<String> mindAtTheEnd(AgentBody body, long now) {
        List<String> mind = new ArrayList<>();
        label(mind, "doing", body.brain().describe());
        label(mind, "going", body.navigator().describe());
        if (!body.setbacks().isEmpty()) {
            label(mind, "setbacks", body.setbacks().describe(now));
        }
        boolean hunger = false;
        for (Gauge gauge : body.needs().all()) {
            hunger |= gauge.kind() == NeedKind.HUNGER;
            mind.add(String.format(Locale.ROOT, "need: %s (pressure %.2f)",
                    gauge.describe(), gauge.pressure()));
        }
        // Only when nothing above already said it: a declared hunger need is a VIEW over this same
        // metabolism and prints more (saturation, exhaustion), so writing both duplicated the line.
        if (!hunger) {
            Metabolism metabolism = body.metabolism();
            mind.add(String.format(Locale.ROOT, "food: %d/%d (saturation %.1f)",
                    metabolism.foodLevel(), Metabolism.MAX_FOOD, metabolism.saturation()));
        }
        return mind;
    }

    /**
     * Files one labelled readout, a line at a time.
     *
     * <p>Some are not one line — the arbiter's {@code describe()} is a whole bid table with newlines
     * in it, and stored whole it made one grave entry print as five unindented ones. Split here
     * rather than at the readout: what is stored is what will be read.
     */
    private static void label(List<String> mind, String label, String readout) {
        boolean first = true;
        for (String line : readout.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            mind.add(first ? label + ": " + line : "  " + line.strip());
            first = false;
        }
    }

    /** What dealt it, by display name — blank when nothing did, as for a fall or a drowning. */
    private static String killerName(DamageSource cause) {
        Entity killer = responsible(cause);
        return killer == null ? "" : killer.getName().getString();
    }

    /**
     * The killer's own handle, when the killer was one of ours or a player — a player's account
     * uuid is their agent id, so "who killed whom" answers across both.
     */
    private static Optional<AgentId> killerId(DamageSource cause) {
        Entity killer = responsible(cause);
        if (killer instanceof AgentBody agent) {
            return Optional.ofNullable(agent.agentId());
        }
        if (killer instanceof Player player) {
            return Optional.of(AgentId.of(player.getUUID()));
        }
        return Optional.empty();
    }

    /**
     * Who is answerable, preferring the shooter over the arrow: an agent shot dead was killed by
     * the archer, and a grave naming the projectile would be a grave nobody can act on.
     */
    private static @Nullable Entity responsible(DamageSource cause) {
        Entity killer = cause.getEntity();
        return killer != null ? killer : cause.getDirectEntity();
    }
}
