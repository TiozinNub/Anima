package dev.luizloyola.anima.mod.rest;

import dev.luizloyola.anima.core.agent.AgentModifiers;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.agent.need.Rest;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * What being tired costs a live body (sleep spec, decision 5): its handling beats through
 * {@link Rest#costOf}, and, exhausted, its walking speed. Re-applied on a level change only.
 */
public final class RestCost {

    private static final Identifier WALK = Identifier.fromNamespaceAndPath("anima", "rest");

    /** The level each body's cost was last set for. Not saved: the first tick after a load sets it. */
    private static final Map<AgentBody, NeedLevel> APPLIED = new WeakHashMap<>();

    private RestCost() {
    }

    /** Keep the body's cost matching its rest; call after the needs tick. */
    public static void keep(AgentBody body, Rest rest) {
        NeedLevel level = rest.level();
        if (APPLIED.put(body, level) == level) {
            return;
        }
        if (body.modifiers() != AgentModifiers.NONE) {
            body.modifiers().remove(Rest.MODIFIER);
            body.modifiers().applyAll(Rest.costOf(level));
        }
        AttributeInstance speed = body.entity().getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(WALK);
            if (Rest.slowsWalking(level)) {
                speed.addTransientModifier(new AttributeModifier(WALK, -Rest.EXHAUSTED_WALK,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
    }
}
