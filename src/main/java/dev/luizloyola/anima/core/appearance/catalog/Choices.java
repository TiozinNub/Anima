package dev.luizloyola.anima.core.appearance.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What values a catalog's parameters may actually take, given the art that exists.
 *
 * <p>A slot names a <b>family</b> — {@code autarkia:person/hair/{hairstyle}} — and nothing lists
 * its members: the art is the list, so drawing a new PNG makes it choosable with no edit anywhere.
 *
 * <h2>A folder of three files can hold one choice</h2>
 * {@code long.png}, {@code long_slim.png} and {@code long_wide.png} are <b>one</b> hairstyle: the
 * suffixed pair are model-specific cuts the selector picks between itself, and {@code long_slim} on
 * a wide body lands a sleeve on the wrong arm. A state is the same — {@code neutral_blink} is
 * another way of drawing an expression, not another expression.
 *
 * <p>So a name ending in a <b>specialiser's</b> value collapses onto its base. A specialiser is a
 * placeholder a rule uses in one candidate and not in its last — {@code model} in
 * {@code ["hair/{hairstyle}_{model}", "hair/{hairstyle}"]} — and keeps its own values.
 *
 * <h2>Why this is Anima's and not a consumer's</h2>
 * All of it is catalog shape (family, candidate list, placeholder, fallback), and it must be
 * <b>one</b> implementation: the editor asks this to fill a picker and the game to roll a genotype,
 * and any disagreement rolls a look the tool cannot show, appearing as a rendering bug.
 */
public final class Choices {
    private Choices() {}

    /**
     * For each templated parameter, the values a file exists for, as choices.
     *
     * @param textureIds every texture id that exists, in the catalog's own id form (no
     *                   {@code textures/} prefix, no extension) — a directory walk on one side, a
     *                   walk of the mod jar on the other, and neither is this class's business.
     */
    public static Map<String, List<String>> of(Catalog catalog, Collection<String> textureIds) {
        return of(catalog, textureIds, Map.of());
    }

    /**
     * As above, but with some parameters already decided.
     *
     * <p>What a family split by another parameter needs: with art at
     * {@code {gender}/shirts/{shirt}}, a picker wants every shirt, while a <b>roll</b> must have
     * only those drawn for that gender or a man is handed a woman's shirt and his torso
     * silently draws nothing.
     *
     * @param known parameters already fixed, substituted into every template before globbing.
     *              Anything not named here is still answered across all of its values.
     */
    public static Map<String, List<String>> of(Catalog catalog, Collection<String> textureIds,
                                               Map<String, String> known) {
        Map<String, List<String>> options = new TreeMap<>();
        Set<String> specialisers = specialisers(catalog);
        Map<String, Set<String>> raw = new TreeMap<>();
        for (SlotSpec slot : catalog.slots()) {
            for (Selector.Rule rule : slot.selector().rules()) {
                // Glob the GENERAL candidate (the last one), because it is the pattern every member
                // of the family matches, specialised or not.
                String general = resolve(rule.textures().get(rule.textures().size() - 1), known);
                for (String key : placeholders(general)) {
                    raw.computeIfAbsent(key, any -> new LinkedHashSet<>())
                            .addAll(valuesFor(general, key, textureIds));
                }
            }
        }
        Set<String> suffixes = specialiserSuffixes(catalog, raw);

        raw.forEach((key, values) -> {
            Set<String> collapsed = new TreeSet<>();
            for (String value : values) {
                collapsed.add(specialisers.contains(key) ? value : withoutSuffix(value, suffixes));
            }
            if (!collapsed.isEmpty()) {
                options.put(key, List.copyOf(collapsed));
            }
        });
        return options;
    }

    /**
     * The values one placeholder takes in one template, read off the ids that exist.
     *
     * <p>Pattern matching rather than a directory listing, so the same code serves a folder on disk
     * and a jar entry.
     */
    public static List<String> valuesFor(String template, String parameter, Collection<String> textureIds) {
        List<String> keys = placeholders(template);
        int group = keys.indexOf(parameter);
        if (group < 0) {
            return List.of();
        }
        // Matched as a pattern rather than split around one placeholder, because a template may
        // carry several. Each placeholder matches within one path segment: a family is a folder,
        // so a value never spans a slash.
        StringBuilder regex = new StringBuilder();
        int cursor = 0;
        for (String key : keys) {
            int open = template.indexOf("{" + key + "}", cursor);
            regex.append(Pattern.quote(template.substring(cursor, open))).append("([^/]+)");
            cursor = open + key.length() + 2;
        }
        regex.append(Pattern.quote(template.substring(cursor)));
        Pattern pattern = Pattern.compile(regex.toString());

        Set<String> found = new TreeSet<>();
        for (String id : textureIds) {
            Matcher matched = pattern.matcher(id);
            if (matched.matches()) {
                found.add(matched.group(group + 1));
            }
        }
        return List.copyOf(found);
    }

    /** A template with the parameters somebody has already decided filled in. */
    private static String resolve(String template, Map<String, String> known) {
        String resolved = template;
        for (Map.Entry<String, String> fixed : known.entrySet()) {
            resolved = resolved.replace("{" + fixed.getKey() + "}", fixed.getValue());
        }
        return resolved;
    }

    /**
     * The suffixes that mark a file as a <em>specialisation</em> of another rather than a choice.
     *
     * <p>A specialised candidate is always the general one plus {@code _something}, where the
     * something is either a placeholder ({@code _{model}}, whose values are slim and wide), or a
     * plain word, {@code _blink}, {@code _speaking}, {@code _open}. Both mean the same to a picker.
     */
    private static Set<String> specialiserSuffixes(Catalog catalog, Map<String, Set<String>> raw) {
        Set<String> suffixes = new LinkedHashSet<>();
        for (SlotSpec slot : catalog.slots()) {
            for (Selector.Rule rule : slot.selector().rules()) {
                List<String> textures = rule.textures();
                String general = textures.get(textures.size() - 1);
                for (int at = 0; at < textures.size() - 1; at++) {
                    String specific = textures.get(at);
                    if (!specific.startsWith(general)) {
                        continue;
                    }
                    String tail = specific.substring(general.length());
                    if (!tail.startsWith("_") || tail.length() < 2) {
                        continue;
                    }
                    String token = tail.substring(1);
                    if (token.startsWith("{") && token.endsWith("}")) {
                        // a placeholder: every value it can take is a suffix
                        suffixes.addAll(raw.getOrDefault(token.substring(1, token.length() - 1), Set.of()));
                    } else {
                        suffixes.add(token);
                    }
                }
            }
        }
        return suffixes;
    }

    /** Parameters that only ever specialise — never a choice in their own right within that rule. */
    private static Set<String> specialisers(Catalog catalog) {
        Set<String> found = new LinkedHashSet<>();
        for (SlotSpec slot : catalog.slots()) {
            for (Selector.Rule rule : slot.selector().rules()) {
                List<String> textures = rule.textures();
                Set<String> general = new LinkedHashSet<>(placeholders(textures.get(textures.size() - 1)));
                for (int at = 0; at < textures.size() - 1; at++) {
                    placeholders(textures.get(at)).stream()
                            .filter(key -> !general.contains(key))
                            .forEach(found::add);
                }
            }
        }
        return found;
    }

    /** {@code long_slim} with {@code slim} known becomes {@code long}; anything else is itself. */
    private static String withoutSuffix(String value, Set<String> suffixes) {
        for (String suffix : suffixes) {
            String tail = "_" + suffix;
            if (value.length() > tail.length() && value.endsWith(tail)) {
                return value.substring(0, value.length() - tail.length());
            }
        }
        return value;
    }

    /** The {@code {names}} in a texture id, in the order they appear. */
    public static List<String> placeholders(String texture) {
        List<String> keys = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int open = texture.indexOf('{', cursor);
            int close = open < 0 ? -1 : texture.indexOf('}', open);
            if (open < 0 || close < 0) {
                return keys;
            }
            keys.add(texture.substring(open + 1, close));
            cursor = close + 1;
        }
    }
}
