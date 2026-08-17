package dev.luizloyola.anima.core.agent.need;

import java.util.List;

/**
 * What is currently acting on a body — the compat half of {@link Vigor}'s source, declared here and
 * supplied by the mod, the same shape as {@code FoodLookup}.
 *
 * <p>Core cannot name a status effect any more than it can name an entity, so what arrives is the
 * shape of one: what it is called, whether it helps, and how much of it there is. A consumer whose
 * creature has some other notion of being buffed supplies that instead.
 */
@FunctionalInterface
public interface Effects {

    /** Nothing acting on this body — the honest answer for a rig with no world behind it. */
    Effects NONE = List::of;

    /** Everything on this body right now, good and bad, in whatever order the body keeps them. */
    List<Effect> active();

    /**
     * One of them.
     *
     * @param nameKey the lang key of its name ({@code effect.minecraft.strength}), not a resolved
     *     string: a readout is translated for whoever is reading it, not for the server's locale
     * @param beneficial whether this is helping — which way it moves the number
     * @param level how many of it, counting from one, so a plain effect is 1
     */
    record Effect(String nameKey, boolean beneficial, int level) {
    }
}
