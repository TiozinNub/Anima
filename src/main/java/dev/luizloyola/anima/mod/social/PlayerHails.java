package dev.luizloyola.anima.mod.social;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Right-clicking somebody is how a player starts a conversation — social foundations §8's
 * targeted hail, made real.
 *
 * <p><b>Targeted, so nothing goes on the bus.</b> A hail is normally a shout: {@code BeingHails}
 * posts a game event and every ear inside the radius picks it up. That is right for somebody
 * calling across a field and wrong for a tap on one shoulder — a settlement turning round as one
 * is not what a right-click means. So exactly one sensor is told, by hand, and the record is what
 * carries the rest. The line itself still sounds and still reaches every nearby ear, because
 * writing it runs {@code Speeches}' listener like any other utterance.
 *
 * <p><b>The record opens NOW, unlike a settler's hail.</b> A shouting settler opens nothing until
 * it arrives; the player is already standing there, and has no brain to hold a clock. The open
 * record is where the wait is measured from ({@code Talkers}' sweep) and what the resume pull bids
 * on once the sensor has spent the hail mark — which it does the moment the body makes the player
 * out at arm's length.
 *
 * <p><b>The body still decides.</b> Nothing here writes into the clicked mind: the hail is a
 * percept, {@code ConverseInstinct} bids on it, and the arbiter grants it at a task boundary or
 * never. Being ignored is that bid losing, and the sweep is what eventually says so out loud.
 */
public final class PlayerHails {

    private PlayerHails() {
    }

    /** Call once from mod init. */
    public static void init() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer talker)
                    || !player.getItemInHand(hand).isEmpty()
                    || !(entity instanceof AgentBody body)
                    || body.agentId() == null || !body.entity().isAlive()) {
                return InteractionResult.PASS;
            }
            return hail(talker, body);
        });
    }

    /**
     * The three things a click can mean, told apart BEFORE joining: already talking to this body
     * (show the buttons again — the menu has scrolled out of chat), already talking to somebody
     * else, or a fresh hail. Asking {@code current()} first rather than reading the join's answer
     * is what keeps them apart: a rejoin and a fresh open both come back present, and the two want
     * opposite things.
     */
    private static InteractionResult hail(ServerPlayer player, AgentBody body) {
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        Speech speech = Talkers.of(server, player);
        Optional<Encounter> mine = speech.current();
        if (mine.isPresent()) {
            if (mine.get().includes(body.agentId())) {
                Talkers.offer(server, player);
            } else {
                aside(player, "anima.talk.busy.you",
                        Speeches.nameFor(server, player, mine.get().other(
                                ContactsSync.idOf(player)).orElse(null)));
            }
            return InteractionResult.SUCCESS;
        }
        if (speech.join(BeingId.of(body.agentId()), Speech.Opening.I_HAILED).isEmpty()) {
            aside(player, "anima.talk.busy.them", Speeches.nameFor(server, player, body.agentId()));
            return InteractionResult.SUCCESS;
        }
        // One ear, not the bus. The mark is spent on arrival like any other hail's, and arrival is
        // already true at arm's length — which is exactly why the record had to be opened above
        // rather than waited for.
        body.beingSense().hailedBy(player);
        return InteractionResult.SUCCESS;
    }

    /** A small on-screen line, social foundations §8 — the action bar, not the transcript. */
    private static void aside(ServerPlayer player, String key, Component whom) {
        player.displayClientMessage(Component.translatable(key, whom)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
    }
}
