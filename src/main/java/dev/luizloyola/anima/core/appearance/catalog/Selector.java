package dev.luizloyola.anima.core.appearance.catalog;

import java.util.ArrayList;
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
     * One rule: the conditions that must all hold, and the texture (or textures) if they do.
     *
     * <p>An empty {@link #when()} is the wildcard — the mandatory last rule that stops an
     * unforeseen combination leaving a hole in a face.
     *
     * <h2>Why more than one texture</h2>
     * A sleeve drawn for four-pixel arms lands on the wrong pixels of a three-pixel one, because
     * narrowing an arm moves its faces on the sheet; a hat does not care. Listing candidates says
     * <b>specific if it exists, shared otherwise</b> in one line.
     *
     * <p>Not expressible as two selector rules: a rule matches on <em>parameters</em>, so a slim
     * rule would match whether or not the file was ever drawn, and the layer would vanish rather
     * than fall back.
     */
    public record Rule(Map<String, String> when, List<String> textures) {
        public Rule {
            when = Map.copyOf(Objects.requireNonNull(when, "when"));
            textures = List.copyOf(Objects.requireNonNull(textures, "textures"));
            if (textures.isEmpty()) {
                throw new IllegalArgumentException("a rule with no texture matches nothing usefully");
            }
        }

        /** The common case: one texture, no alternatives. */
        public Rule(Map<String, String> when, String texture) {
            this(when, List.of(Objects.requireNonNull(texture, "texture")));
        }

        /** The preferred texture — what a single-texture rule has always meant. */
        public String texture() {
            return textures.get(0);
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
        List<String> candidates = candidates(params);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Every texture the matching rule offers, best first, with parameters filled in.
     *
     * <p>Which one is actually drawn depends on what exists on disk, and that is decided where a
     * catalog can be asked — see {@code Catalog.compose}.
     */
    public List<String> candidates(Map<String, String> params) {
        for (Rule rule : rules) {
            if (rule.matches(params)) {
                List<String> filled = new ArrayList<>(rule.textures().size());
                rule.textures().forEach(texture -> filled.add(fill(texture, params)));
                return List.copyOf(filled);
            }
        }
        return List.of();
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
