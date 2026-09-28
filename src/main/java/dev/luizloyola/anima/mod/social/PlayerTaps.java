package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.net.ContactsSync;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Right-clicking somebody is a tap on the shoulder — social foundations §8's initiating click,
 * re-read on 2026-09-14 (decision: Luiz): not a hail called out and waited on, but an instant
 * check of whether this body will be interrupted.
 *
 * <p><b>The mind is asked on the spot.</b> {@code BrainDriver.interruptible} is the arbiter's own
 * rule asked as a question: a body that would be granted the conversation this tick is willing,
 * one that would wait for a task boundary is busy — and the player is told which at once, rather
 * than standing in front of a chopper for a patience clock. "He was busy" stays literally true;
 * it is just said now.
 *
 * <p><b>Willing opens the record quiet.</b> No hail line — nobody shouted. The body hears the tap
 * (its sensor gets a heard track at the player's position, nothing more), the resume pull bids on
 * the open record, and the body turns and greets on its next tick. The panel opens on the click,
 * its acts go live under that greeting on the beat, and the player leads from there.
 *
 * <p>Nothing here writes into the clicked mind, still: the question is read off the arbiter, and
 * the answer is the arbiter's.
 */
public final class PlayerTaps {

    private PlayerTaps() {
    }

    /** Call once from mod init. */
    public static void init() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer talker)
                    || !(entity instanceof AgentBody body)
                    || body.agentId() == null || !body.entity().isAlive()) {
                return InteractionResult.PASS;
            }
            // A spectator is not there to talk to, so the click opens the inventory instead, look-only
            // because vanilla refuses a spectator's slot clicks. Fabric fires this before vanilla's
            // own spectator check, hence the ask here.
            if (player.isSpectator()) {
                return body.showInventory(talker) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            if (!player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
            return tap(talker, body);
        });
    }

    /**
     * The four things a click can mean, told apart in order: already talking to this body (open
     * the panel again — into a record a settler may have opened by walking up), already talking
     * to somebody else,
     * this body busy — with somebody else, or with what it is doing — and a fresh tap.
     */
    private static InteractionResult tap(ServerPlayer player, AgentBody body) {
        MinecraftServer server = player.level().getServer();
        Speech speech = Talkers.of(server, player);
        AgentId id = body.agentId();
        Optional<Encounter> mine = speech.current();
        if (mine.isPresent()) {
            if (mine.get().includes(id)) {
                Talkers.open(server, player);
            } else {
                aside(player, "anima.talk.busy.you",
                        Speeches.nameFor(server, player, mine.get().other(
                                ContactsSync.idOf(player)).orElse(null)));
            }
            return InteractionResult.SUCCESS;
        }
        if (EncounterData.get(server).roster().openFor(id).isPresent()) {
            aside(player, "anima.talk.busy.them", Speeches.nameFor(server, player, id));
            return InteractionResult.SUCCESS;
        }
        if (!body.brain().interruptible()) {
            aside(player, "anima.talk.busy.now", Speeches.nameFor(server, player, id));
            return InteractionResult.SUCCESS;
        }
        if (speech.join(BeingId.of(id), Speech.Opening.QUIET).isEmpty()) {
            aside(player, "anima.talk.busy.them", Speeches.nameFor(server, player, id));
            return InteractionResult.SUCCESS;
        }
        body.beingSense().tappedBy(player);
        Talkers.open(server, player);
        return InteractionResult.SUCCESS;
    }

    /** Why the click did nothing, on the action bar. */
    private static void aside(ServerPlayer player, String key, Component whom) {
        Speeches.aside(player, Component.translatable(key, whom)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
