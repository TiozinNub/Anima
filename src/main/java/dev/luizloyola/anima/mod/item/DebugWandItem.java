package dev.luizloyola.anima.mod.item;

import dev.luizloyola.anima.compat.Players;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.command.AgentSelection;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.debug.DebugView;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.body.AgentBodies;
import java.util.EnumSet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A development tool that <em>selects</em> a {@link AgentBody}: right-clicking one pins that person
 * to the player's slot in {@link AgentSelection}, the slot {@code /autarkia select} uses.
 * Per-player rather than per stack (the item is stateless), server-side, and mirrored to the client
 * only for the glow. Right-clicking a block sends the pinned person walking there ({@link #useOn}).
 */
public class DebugWandItem extends Item {
    public DebugWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        // Mutate only on the server (the ServerPlayer cast implies server); the client returns SUCCESS
        // to predict the arm swing, mirroring the selection path in interactLivingEntity.
        if (level.isClientSide() || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        AgentId selected = AgentSelection.pinned(player).orElse(null);
        if (selected == null) {
            Players.overlay(player, Component.translatable("item.anima.debug_wand.no_selection"));
            return InteractionResult.SUCCESS;
        }
        AgentBody person = AgentBodies.findLoaded(player.level().getServer(), selected);
        if (person == null) {
            Players.overlay(player, Component.translatable("item.anima.debug_wand.not_loaded"));
            return InteractionResult.SUCCESS;
        }
        // Stand on top of the clicked face.
        Vec3 target = Vec3.atBottomCenterOf(context.getClickedPos().relative(context.getClickedFace()));
        // The gait is advisory all the way down — the follower still slows for careful ground and
        // still takes a leap's run-up at full speed — so a run is a request, not a cliff dive.
        boolean hurry = player.isSecondaryUseActive();
        person.navigateTo(target, hurry ? Gait.SPRINT : Gait.WALK);
        Players.overlay(player, Component.translatable(
                hurry ? "item.anima.debug_wand.running" : "item.anima.debug_wand.moving",
                person.entity().getName(), (int) target.x, (int) target.y, (int) target.z));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof AgentBody person)) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            if (player instanceof ServerPlayer serverPlayer) {
                cycleDebugLayer(serverPlayer, person);
            }
            return InteractionResult.SUCCESS;
        }
        // Mutate only on the server (the ServerPlayer cast implies server); the client returns
        // SUCCESS to predict the arm swing. The pin mirrors to the client for the glow.
        if (player instanceof ServerPlayer serverPlayer) {
            AgentId id = person.agentId();
            if (id == null) {
                Players.overlay(serverPlayer,
                        Component.translatable("item.anima.debug_wand.no_identity"));
            } else {
                AgentSelection.pin(serverPlayer, id);
                Players.overlay(serverPlayer,
                        Component.translatable("item.anima.debug_wand.selected", person.entity().getName()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The wand's debug-view cycle: shift-click a AgentBody to walk their layers one at a time —
     * path, brain, memory, peers, then off. It REPLACES the layer set rather than adding to it
     * (decision: Luiz); {@code /autarkia debug} is the only way to have several up at once.
     * Clicking pins them first, so the view draws whoever was last clicked.
     */
    private static void cycleDebugLayer(ServerPlayer player, AgentBody person) {
        AgentId id = person.agentId();
        if (id == null) {
            Players.overlay(player, Component.translatable("item.anima.debug_wand.no_identity"));
            return;
        }
        MinecraftServer server = player.level().getServer();
        boolean reselected = !id.equals(AgentSelection.pinned(player).orElse(null));
        if (reselected) {
            AgentSelection.pin(player, id);
        }
        // The cycle only CONTINUES from a single showing layer: with several up there is no
        // "current rung" to advance from, so the wand restarts at the first rather than guessing.
        EnumSet<DebugLayer> showing = DebugView.layers(server, player.getUUID());
        DebugLayer current = reselected || showing.size() != 1
                ? null
                : showing.iterator().next();
        DebugLayer next = DebugLayer.next(current).orElse(null);
        DebugView.replace(server, player.getUUID(),
                next == null ? EnumSet.noneOf(DebugLayer.class) : EnumSet.of(next));
        Players.overlay(player, next == null
                ? Component.translatable("item.anima.debug_wand.debug_off", person.entity().getName())
                : Component.translatable("item.anima.debug_wand.debug_layer",
                        person.entity().getName(), next.key()));
    }
}
