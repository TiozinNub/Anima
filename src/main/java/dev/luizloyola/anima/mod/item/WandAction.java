package dev.luizloyola.anima.mod.item;

import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * What clicking a block with the debug wand MEANS to the selected agent — one verb, supplied by
 * the mod that knows what the block is.
 *
 * <p>Anima's wand can point at anything and knows what none of it is, so it asks rather than
 * decides: every registered action gets the click in turn, and the first to <em>claim</em> it wins.
 * Navigating there is the library's own answer and is not registered, so it can never be shadowed.
 *
 * <p><b>An action scopes itself.</b> There is no species key to register under —
 * {@link dev.luizloyola.anima.core.agent.SpeciesProfile} is explicit that the key is a config path
 * and a readout label that Anima never branches on. An action asks whether {@link Click#agent()} is
 * one of its own bodies and returns empty otherwise, so a pets mod's actions and a settler mod's
 * coexist in one registry.
 *
 * <p>Returning empty is the ordinary case, not a failure — it is {@code PASS}, and the click falls
 * through to the next action and finally to walking there.
 */
@FunctionalInterface
public interface WandAction {

    /**
     * What this agent should do about the clicked block, and the line to show the operator —
     * or empty to pass, leaving the click to the next action.
     *
     * <p>Called on the server thread with the world loaded, so a live block read is safe. An
     * implementation must decline before it changes anything: the first non-empty answer ends the
     * search, so anything done on the way to returning empty happens on a click somebody else
     * handled.
     */
    Optional<Component> useOn(Click click);

    /**
     * One wand click on a block.
     *
     * <p>A record rather than five parameters so that a later click detail — the hand, the exact
     * hit vector — can be carried without every consumer's action failing to compile.
     *
     * @param agent  the pinned body this click is FOR, already resolved and loaded
     * @param player whose wand it is; the one who sees the returned line
     * @param level  the world the BLOCK is in, which is the player's — an action that cares
     *               whether the agent is in it too must compare, since the wand happily points
     *               across dimensions
     * @param block  the clicked cell
     * @param face   the face it was clicked on
     */
    record Click(AgentBody agent, ServerPlayer player, ServerLevel level, BlockPos block,
                 Direction face) {
    }
}
