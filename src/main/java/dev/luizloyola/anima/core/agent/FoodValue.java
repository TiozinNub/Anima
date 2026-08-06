package dev.luizloyola.anima.core.agent;

/**
 * What eating one of an item does to the body — vanilla {@code FoodProperties}' exact fields as
 * pure core data, supplied by {@code compat} through the brain's {@code FoodLookup} sense. Core
 * Never hardcodes a food table, so vanilla and modded foods alike arrive through the registry.
 *
 * <p>These are the numbers {@link Metabolism#eat(int, float)} takes; {@code saturation} is the
 * PRECOMPUTED value, not the legacy modifier (see {@link Metabolism#saturationByModifier(int, float)}).
 *
 * @param nutrition    food points restored (bread 5, steak 8)
 * @param saturation   precomputed saturation restored (bread 6.0, steak 12.8)
 * @param canAlwaysEat vanilla's edible-when-full flag (golden apples, chorus fruit); no eating
 *                     method uses it yet (a Person only eats when hungry), but it is carried for
 *                     fidelity so future methods (panic-heal gapple, chorus escape) read the real
 *                     flag
 */
public record FoodValue(int nutrition, float saturation, boolean canAlwaysEat) {
}
