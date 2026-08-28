package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.mod.AnimaMod;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * The speech channel — {@code anima:being_speech}, a REGISTERED game event for a line spoken into
 * a live encounter.
 *
 * <p><b>Separate from {@link BeingVoices} for the same reason {@link BeingHails} is</b>:
 * {@code GameEvent.Context} carries only a source entity and a blockstate, so the only place a
 * range can live is the event's own registration. A spoken line posted on the voice channel would
 * be swallowed a third of the way out, and speech's range ({@code social.chat_radius}) is not the
 * voice range anyway.
 *
 * <p>Initialised by Anima itself, like {@link BeingHails}: {@code BrainDriver}'s {@code Speech}
 * port is on the library's own root, so a bare install must be able to converse.
 */
public final class BeingSpeech {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "being_speech");
    public static final ResourceKey<GameEvent> KEY = ResourceKey.create(Registries.GAME_EVENT, ID);

    /**
     * The widest chat range any species may ask for, not any one species' default — see
     * {@link BeingHails#RADIUS} for why registration must cover the maximum rather than a default.
     */
    public static final int RADIUS = (int) ProfileAspect.SOCIAL_CHAT_RADIUS.max();

    private static Holder<GameEvent> speech;

    private BeingSpeech() {
    }

    /** Call once from mod init, before any level exists. */
    public static void init() {
        speech = Registry.registerForHolder(BuiltInRegistries.GAME_EVENT, KEY, new GameEvent(RADIUS));
    }

    /**
     * This body just said a line — put it on the bus and make the noise.
     *
     * <p>The sound is a BORROWED asset, same as {@link BeingHails#hailed}: villager vocalisations
     * are the closest thing vanilla has to a humanoid line, quieter than a hail because this is
     * ordinary conversation, not a shout.
     */
    public static void spoke(LivingEntity body) {
        if (speech == null || body.level().isClientSide()) {
            return;
        }
        body.level().playSound(null, body.blockPosition(), SoundEvents.VILLAGER_AMBIENT,
                SoundSource.NEUTRAL, 0.5F, 1.0F);
        body.level().gameEvent(speech, body.position(), GameEvent.Context.of(body));
    }
}
