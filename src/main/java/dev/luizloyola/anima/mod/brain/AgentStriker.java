package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.agent.Melee;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.act.Striker;
import dev.luizloyola.anima.core.brain.act.Sweep;
import dev.luizloyola.anima.core.brain.act.ToolChoice;
import dev.luizloyola.anima.core.brain.act.WeaponChoice;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.social.PartyData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
    public Reach reach(BeingId target, double inset) {
        LivingEntity self = body.entity();
        LivingEntity victim = find(target);
        if (victim == null || victim.level() != self.level()) {
            return Reach.GONE;
        }
        if (victim.isDeadOrDying()) {
            return Reach.DEAD;
        }
        if (!Melee.withinReach(self, victim, inset)) {
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

    @Override
    public boolean draw(BeingId target) {
        Inventory inv = body.inventory();
        int choice = choose(find(target));
        if (choice == ToolChoice.KEEP_HAND) {
            return false;
        }
        if (choice == ToolChoice.BARE_HAND) {
            inv.stow();
            return inv.mainHand().isEmpty(); // a full pack keeps the hand as it is
        }
        inv.wield(choice);
        return true;
    }

    /**
     * The hit of the weapon {@link #draw} would put in hand against {@code target}: what a fight
     * with it would be fought with. Wear is left out — the balance weighs a whole fight, not one
     * weapon's last blows.
     */
    public Melee.Hit bestHit(@Nullable LivingEntity target) {
        Inventory inv = body.inventory();
        LivingEntity self = body.entity();
        int choice = choose(target);
        int slot = choice == ToolChoice.KEEP_HAND
                ? Inventory.HOTBAR_START + inv.selectedSlot() : choice;
        ItemStack stack = choice == ToolChoice.BARE_HAND
                ? ItemStack.EMPTY
                : ItemStacks.toVanilla(inv.get(slot), self.level().registryAccess());
        return measure(self, stack, target);
    }

    /** {@link WeaponChoice} over the pack as it is now, against {@code target} if there is one. */
    private int choose(@Nullable LivingEntity target) {
        Inventory inv = body.inventory();
        LivingEntity self = body.entity();
        HolderLookup.Provider registries = self.level().registryAccess();
        List<WeaponChoice.Candidate> pack = new ArrayList<>();
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            dev.luizloyola.anima.core.inv.ItemStack core = inv.get(slot);
            if (!core.isEmpty()) {
                ItemStack stack = ItemStacks.toVanilla(core, registries);
                Melee.Hit hit = measure(self, stack, target);
                pack.add(new WeaponChoice.Candidate(slot, hit.damage(), hit.perSecond(),
                        Melee.blowsLeft(stack)));
            }
        }
        Melee.Hit fist = Melee.hit(self, ItemStack.EMPTY);
        WeaponChoice.Candidate bare = new WeaponChoice.Candidate(ToolChoice.BARE_HAND,
                fist.damage(), fist.perSecond(), WeaponChoice.UNBREAKING);
        WeaponChoice.Foe foe = target == null || target.isDeadOrDying() ? null
                : new WeaponChoice.Foe(target.getHealth(), target.getArmorValue(),
                        target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        return WeaponChoice.choose(pack, Inventory.HOTBAR_START + inv.selectedSlot(), bare, foe,
                body.profile().i(ProfileAspect.HANDLING_STACK_TICKS) / 20.0);
    }

    private static Melee.Hit measure(LivingEntity self, ItemStack stack, @Nullable LivingEntity target) {
        return target != null && self.level() instanceof ServerLevel level
                ? Melee.hit(level, self, stack, target) : Melee.hit(self, stack);
    }

    /** The charge counter, for the body's save: a reload must not cost a fighter its warmup. */
    public int snapshot() {
        return Melee.chargeTicks(body.entity());
    }

    public void restore(int chargeTicks) {
        Melee.setChargeTicks(body.entity(), chargeTicks);
        adoptHand = true; // the hand may be filled after this, and what was held is still held
    }

    private @Nullable LivingEntity find(BeingId id) {
        return body.level() instanceof ServerLevel level ? find(level, id) : null;
    }

    /**
     * The body a being id names in {@code level}, dying or not. A creature's id and a player's are
     * the entity's own uuid; an agent's is its agent id, found through the loaded bodies.
     */
    public static @Nullable LivingEntity find(ServerLevel level, BeingId id) {
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
