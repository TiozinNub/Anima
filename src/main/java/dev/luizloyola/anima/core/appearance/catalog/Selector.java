package dev.luizloyola.anima.core.appearance.catalog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which texture a slot wears, given what is currently true about the agent.
 *
 * <p>An ordered rule list, first match wins; each rule names the parameters it cares about and
 * ignores every other, so eyes can follow mood while the mouth follows mood <em>and</em> speech,
 * from one parameter map.
 *
 * <p>Textures may template parameters into the id — {@code autarkia:hair/{style}} — so one rule
 * covers a whole family of variants.
 */
public record Selector(List<Rule> rules) {
    public Selector {
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    /**
     * One rule: the conditions that must all hold, and the texture if they do.
     *
     * <p>An empty {@link #when()} is the wildcard — the mandatory last rule that stops an
     * unforeseen combination from leaving a hole in a face.
     */
    public record Rule(Map<String, String> when, String texture) {
        public Rule {
            when = Map.copyOf(Objects.requireNonNull(when, "when"));
            Objects.requireNonNull(texture, "texture");
        }

        public boolean matches(Map<String, String> params) {
            for (Map.Entry<String, String> condition : when.entrySet()) {
                if (!condition.getValue().equals(params.get(condition.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * The texture for these parameters, or {@code null} if no rule matched — which the compositor
     * treats as "this slot draws nothing right now", a legitimate answer for a slot like blood or
     * grime that is usually absent.
     */
    public @Nullable String pick(Map<String, String> params) {
        for (Rule rule : rules) {
            if (rule.matches(params)) {
                return fill(rule.texture(), params);
            }
        }
        return null;
    }

    /** Whether a wildcard rule exists at all — the editor warns when one does not. */
    public boolean hasFallback() {
        return rules.stream().anyMatch(rule -> rule.when().isEmpty());
    }

    /** Substitute {@code {param}} placeholders from the parameter map; unknown ones are left alone
     *  so they show up as a missing texture rather than as a silently wrong one. */
    static String fill(String texture, Map<String, String> params) {
        if (texture.indexOf('{') < 0) {
            return texture;
        }
        StringBuilder out = new StringBuilder(texture.length());
        int cursor = 0;
        while (cursor < texture.length()) {
            int open = texture.indexOf('{', cursor);
            int close = open < 0 ? -1 : texture.indexOf('}', open);
            if (open < 0 || close < 0) {
                out.append(texture, cursor, texture.length());
                break;
            }
            out.append(texture, cursor, open);
            String key = texture.substring(open + 1, close);
            String value = params.get(key);
            out.append(value == null ? texture.substring(open, close + 1) : value);
            cursor = close + 1;
        }
        return out.toString();
    }
}
