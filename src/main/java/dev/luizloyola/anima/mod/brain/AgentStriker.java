package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.agent.Melee;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.brain.act.Striker;
import dev.luizloyola.anima.core.brain.act.Sweep;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.social.PartyData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A body's fighting arm, as the brain's {@link Striker} port: vanilla's melee blow
 * ({@link Melee}) with the sweep kept off anybody the combat spec says it must spare.
 *
 * <p>Owned and ticked by the body, like the breaker: the charge counts on every tick whether or not
 * anything is fighting, so a body that draws its sword mid-mining still waits out the warmup.
 */
public final class AgentStriker implements Striker {

    private final AgentBody body;
    private ItemStack lastMainHand = ItemStack.EMPTY;
    /** Set by a restore: the next tick takes whatever is in hand as what was always there. */
    private boolean adoptHand;

    public AgentStriker(AgentBody body) {
        this.body = body;
    }

    /**
     * Once per body tick: counts the charge, and starts it over when the hand changes to a
     * different item — {@code Player.tick}'s rule, where the same item worn a little further does
     * not count as a change.
     */
    public void tick() {
        LivingEntity self = body.entity();
        Melee.advanceCharge(self);
        ItemStack hand = self.getMainHandItem();
        if (!ItemStack.matches(lastMainHand, hand)) {
            if (!adoptHand && !ItemStack.isSameItem(lastMainHand, hand)) {
                Melee.resetCharge(self);
            }
            lastMainHand = hand.copy();
        }
        adoptHand = false;
    }

    @Override
    public Reach reach(BeingId target) {
        LivingEntity self = body.entity();
        LivingEntity victim = find(target);
        if (victim == null || victim.level() != self.level()) {
            return Reach.GONE;
        }
        if (victim.isDeadOrDying()) {
            return Reach.DEAD;
        }
        if (!Melee.withinReach(self, victim)) {
            return Reach.OUT_OF_REACH;
        }
        return self.hasLineOfSight(victim) ? Reach.IN_REACH : Reach.BLOCKED;
    }

    @Override
    public double charge() {
        return Melee.charge(body.entity());
    }

    @Override
    public boolean strike(BeingId target) {
        if (reach(target) != Reach.IN_REACH || !(body.level() instanceof ServerLevel level)) {
            return false;
        }
        LivingEntity victim = find(target);
        Sweep.Kind targetKind = kindOf(victim);
        PartyId targetParty = partyOf(level, victim);
        boolean landed = Melee.strike(level, body.entity(), victim, nearby -> Sweep.catches(
                targetKind, targetParty, kindOf(nearby), partyOf(level, nearby)));
        if (landed) {
            body.metabolism().exhaust(Metabolism.EXHAUSTION_ATTACK);
        }
        return landed;
    }

    /** The charge counter, for the body's save: a reload must not cost a fighter its warmup. */
    public int snapshot() {
        return Melee.chargeTicks(body.entity());
    }

    public void restore(int chargeTicks) {
        Melee.setChargeTicks(body.entity(), chargeTicks);
        adoptHand = true; // the hand may be filled after this, and what was held is still held
    }

    /**
     * The body this id names, dying or not. A creature's id and a player's are the entity's own
     * uuid; an agent's is its agent id, found through the loaded bodies.
     */
    private @Nullable LivingEntity find(BeingId id) {
        if (!(body.level() instanceof ServerLevel level)) {
            return null;
        }
        if (level.getEntity(id.value()) instanceof LivingEntity living) {
            return living;
        }
        AgentId agent = id.asPerson();
        for (AgentBody other : AgentBodies.snapshot(level.getServer())) {
            if (agent.equals(other.agentId())) {
                return other.entity();
            }
        }
        return null;
    }

    private static Sweep.Kind kindOf(LivingEntity entity) {
        if (entity instanceof AgentBody) {
            return Sweep.Kind.AGENT;
        }
        return entity instanceof Player ? Sweep.Kind.PLAYER : Sweep.Kind.OTHER;
    }

    /**
     * The party that decides a sweep: null only for a player in none. Read, never minted — an agent
     * with no party on record is alone in one of its own, named by its own id.
     */
    private static @Nullable PartyId partyOf(ServerLevel level, LivingEntity entity) {
        if (entity instanceof AgentBody agent) {
            AgentId id = agent.agentId();
            if (id == null) {
                return PartyId.of(entity.getUUID());
            }
            return PartyData.get(level.getServer()).currentPartyOf(id)
                    .orElseGet(() -> PartyId.of(id.value()));
        }
        if (entity instanceof Player player) {
            return PartyData.get(level.getServer()).currentPartyOf(AgentId.of(player.getUUID()))
                    .orElse(null);
        }
        return null;
    }
}
