package dev.luizloyola.anima.core.appearance.catalog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * How likely each member of a family is to be chosen — a rare hat, a common shirt.
 *
 * <h2>Why this is not a ladder</h2>
 * A colour ladder is a spectrum, so a hump in it means something. A family has no middle: its
 * members are discovered by walking a folder, so their <em>order</em> is an accident of the
 * filesystem. An unweighted family is therefore drawn <b>uniformly</b>, and a weighted one has to
 * say every number out loud.
 *
 * <h2>Declared, because a family is discovered</h2>
 * Nothing lists a family's members; drawing a PNG adds one. Odds are a <b>map by name</b>, not a
 * parallel array with nothing to be parallel to, and {@link #DEFAULT_KEY} sets what an unnamed
 * member is worth:
 *
 * <pre>
 *   "odds": { "hairstyle": { "*": 10, "mohawk": 1 } }
 * </pre>
 *
 * <p>So a new hairstyle dropped into the folder is common by default. Naming a member the family
 * does not have is harmless.
 */
public record ChoiceWeights(Map<String, Integer> byValue, int fallback) {

    /** The key that sets what an unlisted member is worth. */
    public static final String DEFAULT_KEY = "*";

    /** What a family with nothing said about it uses: every member equally likely. */
    public static final ChoiceWeights UNIFORM = new ChoiceWeights(Map.of(), 1);

    public ChoiceWeights {
        byValue = Map.copyOf(Objects.requireNonNull(byValue, "byValue"));
        if (fallback < 0) {
            throw new IllegalArgumentException("a default weight may not be negative: " + fallback);
        }
        for (Map.Entry<String, Integer> weight : byValue.entrySet()) {
            if (weight.getValue() < 0) {
                throw new IllegalArgumentException(
                        "weight for '" + weight.getKey() + "' may not be negative: " + weight.getValue());
            }
        }
    }

    /** What one member is worth — its own number, or the default for anything unnamed. */
    public int weightOf(String value) {
        return byValue.getOrDefault(value, fallback);
    }

    /**
     * One member, drawn against these odds.
     *
     * <p>Only the values actually offered are considered, so a weight naming art that is not there
     * cannot skew the rest.
     *
     * @return the chosen member, or {@code null} if there was nothing to choose from or every
     *         available member was weighted to zero. A caller that gets null should leave the
     *         parameter unanswered rather than invent one.
     */
    public String pick(List<String> values, RandomGenerator random) {
        if (values.isEmpty()) {
            return null;
        }
        int total = 0;
        for (String value : values) {
            total += weightOf(value);
        }
        if (total <= 0) {
            // Every option switched off: drawing nothing is the right answer for an optional slot.
            return null;
        }
        int roll = random.nextInt(total);
        for (String value : values) {
            roll -= weightOf(value);
            if (roll < 0) {
                return value;
            }
        }
        return values.get(values.size() - 1);
    }
}
