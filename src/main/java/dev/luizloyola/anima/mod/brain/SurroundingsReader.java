package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.WorldClocks;
import dev.luizloyola.anima.core.brain.sense.Surroundings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;

/**
 * The sky, the hour and the light where a body stands — any body: a settler's percepts read it,
 * and so does a player's seat, so both talk about the same weather.
 *
 * <p>Read fresh on every ask: a conversation asks once per line, and each read is a handful of
 * lookups at one block. Weather is told by the biome underfoot, so a desert stays dry in rain and
 * a mountain top gets snow.
 */
public final class SurroundingsReader {

    private SurroundingsReader() {
    }

    public static Surroundings of(LivingEntity body) {
        Level level = body.level();
        BlockPos eyes = BlockPos.containing(body.getEyePosition());
        return new Surroundings(weatherAt(level, eyes),
                Surroundings.DayPhase.of(WorldClocks.timeOfDay(level)),
                level.getMaxLocalRawBrightness(eyes),
                level.getBrightness(LightLayer.SKY, eyes) > 0);
    }

    private static Surroundings.Weather weatherAt(Level level, BlockPos at) {
        if (!level.isRaining()) {
            return Surroundings.Weather.CLEAR;
        }
        Biome.Precipitation falling = level.getBiome(at).value()
                .getPrecipitationAt(at, level.getSeaLevel());
        return switch (falling) {
            case NONE -> Surroundings.Weather.CLEAR;
            case SNOW -> Surroundings.Weather.SNOW;
            case RAIN -> level.isThundering() ? Surroundings.Weather.THUNDER
                    : Surroundings.Weather.RAIN;
        };
    }
}
