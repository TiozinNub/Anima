package dev.luizloyola.anima.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A ticket's level climbs by one per chunk from its centre, and a non-player entity ticks only where
 * both the simulation and the loading level are entity-ticking.
 */
class ChunkTicketsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int levelAt(int radius, int chunksOut) {
        return ChunkLevel.byStatus(FullChunkStatus.FULL) - radius + chunksOut;
    }

    @Test
    void entitiesTickInATrueFiveByFive() {
        assertTrue(ChunkLevel.isEntityTicking(levelAt(ChunkTickets.SIMULATION_RADIUS, 2)));
        assertTrue(ChunkLevel.isEntityTicking(levelAt(ChunkTickets.LOADING_RADIUS, 2)));
    }

    @Test
    void entitiesStopTickingPastTwoChunks() {
        assertFalse(ChunkLevel.isEntityTicking(levelAt(ChunkTickets.SIMULATION_RADIUS, 3)));
    }
}
