package dev.luizloyola.anima.core.brain.history;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a mod's lang file owes its doings — the check behind "no action goes unwritten". The
 * compiler already makes every instinct and work item name a doing; this makes every remembered
 * doing have its words. Each mod runs it over its own doings and its own {@code en_us.json}.
 */
public final class DoingLines {

    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?([a-zA-Z%])");
    private static final Pattern ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private DoingLines() {
    }

    /**
     * Every problem with the remembered doings whose lang key starts with {@code prefix}: a missing
     * line, or an argument that is not positional or points past the doing's slots and its
     * {@link When}. Empty when there are none.
     */
    public static List<String> problems(String prefix, Map<String, String> lang) {
        List<String> out = new ArrayList<>();
        for (Doing doing : Doings.all()) {
            if (!doing.remembered() || !doing.langKey().startsWith(prefix)) {
                continue;
            }
            for (int variant = 1; variant <= Doing.VARIANTS; variant++) {
                String key = doing.langKey() + "." + variant;
                String line = lang.get(key);
                if (line == null) {
                    out.add(key + " is missing");
                    continue;
                }
                Matcher m = ARG.matcher(line);
                while (m.find()) {
                    if (m.group().equals("%%")) {
                        continue;
                    }
                    int position = m.group(1) == null ? -1 : Integer.parseInt(m.group(1));
                    if (position < 1 || position > doing.whenPosition() || !m.group(2).equals("s")) {
                        out.add(key + " uses " + m.group() + " — " + doing.key() + " takes "
                                + doing.slots() + " then when, as %1$s to %" + doing.whenPosition()
                                + "$s");
                    }
                }
            }
        }
        return out;
    }

    /** A lang file's keys and values, scraped as {@code LangSyncTest} scrapes them. */
    public static Map<String, String> load(Class<?> anchor, String resource) {
        String text;
        try (InputStream in = anchor.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("missing resource " + resource);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = ENTRY.matcher(text);
        while (m.find()) {
            found.put(m.group(1), m.group(2));
        }
        return found;
    }
}
