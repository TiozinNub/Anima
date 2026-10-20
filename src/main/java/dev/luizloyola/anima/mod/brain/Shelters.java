package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.SenseEvent;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * What a body remembers of shelters, and a way for the consuming mod to show it one (shelter spec).
 */
public final class Shelters {

    private Shelters() {
    }

    /**
     * Has {@code body} learn how the space at {@code from} opens, as though it had stopped there:
     * a settler shown the hut its party built remembers it without walking in. Feeds only the
     * body's shelter memory; what the body makes of where it stands stays its own check's.
     *
     * <p>Searched off the server thread like the body's own check, and remembered back on it.
     * Completes empty when the body has no level to ask about.
     */
    public static CompletableFuture<Enclosure> inspect(AgentBody body, BlockPos from) {
        if (!(body.level() instanceof ServerLevel level)) {
            return CompletableFuture.completedFuture(Enclosure.UNKNOWN);
        }
        MinecraftServer server = level.getServer();
        return PathfinderService.enclosure(level, from, MoveCapabilities.of(body.profile()), null,
                        false)
                .result()
                .thenApplyAsync(answer -> {
                    remember(body, answer);
                    return answer;
                }, server)
                .exceptionally(failed -> {
                    AnimaMod.LOGGER.warn("enclosure inspection failed", failed);
                    return Enclosure.UNKNOWN;
                });
    }

    /** Writes down the ways into a shelter this answer found, and forgets the ones it disproved. */
    static void remember(AgentBody body, Enclosure answer) {
        AgentId self = body.agentId();
        MinecraftServer server = body.level().getServer();
        if (self == null || server == null) {
            return;
        }
        KnowledgeData data = KnowledgeData.get(server);
        List<SenseEvent> events = ShelterNoter.note(answer, data.registry().forPerson(self),
                AgentKnowledge.maxPerKind(body.profile()));
        if (events.isEmpty()) {
            return;
        }
        data.setDirty();
        String name = body.entity().getName().getString();
        for (SenseEvent event : events) {
            body.journal().record(Category.SENSE,
                    event.type() == SenseEvent.Type.NOTED ? "noticed" : "forgot",
                    PoiSensor.describe(event));
            KnowledgeViewer.onEvent(server, self, name, event);
        }
    }
}
