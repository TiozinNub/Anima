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
 * The hail channel — {@code anima:being_hail}, a REGISTERED game event for a deliberate shout.
 *
 * <p><b>Separate from {@link BeingVoices} because loudness cannot ride the payload</b>:
 * {@code GameEvent.Context} carries only a source entity and a blockstate, so the only place a
 * range can live is the event's own registration. A hail posted on the voice channel would be
 * swallowed a third of the way out.
 *
 * <p>Unlike the voice channel, this one is initialised by Anima itself — the {@code Voice} port
 * and {@code /anima brain hail} are on the library's own root, so a bare install must be able to
 * call out. {@code BeingVoices} is a consumer's to start because only a consumer's mixins post it.
 */
public final class BeingHails {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "being_hail");
    public static final ResourceKey<GameEvent> KEY = ResourceKey.create(Registries.GAME_EVENT, ID);

    /**
     * The widest hail any species may ask for, not any one species' default. Registration happens
     * once at bootstrap and {@code social.hail_radius} is per-species and editable at runtime, so
     * anything narrower loses hails to a runtime retune with nothing in any log. Guarded by
     * {@code BeingHailsTest}.
     */
    public static final int RADIUS = (int) ProfileAspect.SOCIAL_HAIL_RADIUS.max();

    private static Holder<GameEvent> hail;

    private BeingHails() {
    }

    /** Call once from mod init, before any level exists. */
    public static void init() {
        hail = Registry.registerForHolder(BuiltInRegistries.GAME_EVENT, KEY, new GameEvent(RADIUS));
    }

    /**
     * This body just called out — put it on the bus and make the noise.
     *
     * <p>The sound is a BORROWED asset (the permanent one is an open fork in the social spec);
     * villager vocalisations are the closest thing vanilla has to a humanoid shout, and
     * {@code BrainDriver} already borrows one to celebrate.
     */
    public static void hailed(LivingEntity body) {
        if (hail == null || body.level().isClientSide()) {
            return;
        }
        body.level().playSound(null, body.blockPosition(), SoundEvents.VILLAGER_CELEBRATE,
                SoundSource.NEUTRAL, 1.0F, 1.0F);
        body.level().gameEvent(hail, body.position(), GameEvent.Context.of(body));
    }
}
