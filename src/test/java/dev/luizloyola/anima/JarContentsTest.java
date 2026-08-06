package dev.luizloyola.anima;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luizloyola.anima.arch.ModJar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the jar this build produced, rather than the source it was built from: a licence that did
 * not get packaged, a mixin config naming a class that no longer exists, a dependency that quietly
 * came along. None of those fail a compile or show up in {@code src/}.
 *
 * <p>Autarkia runs a near-duplicate over its own jar — the two mods ship under different licences,
 * and only one of them pins the other's version.
 */
class JarContentsTest {

    private static final String MOD_ID = "anima";
    private static final String LICENCE = "MPL-2.0";

    /**
     * The licence text's own first line. Asserted because {@code fabric.mod.json} declaring
     * {@code MPL-2.0} beside a file that is some other licence has consequences outside the code.
     */
    private static final String LICENCE_HEADING = "Mozilla Public License Version 2.0";

    /**
     * Where a jar entry is allowed to be. Anything outside this is a file that arrived without
     * anyone deciding it should — a shaded dependency, a stray asset, a jar-in-jar. Jar-in-jar in
     * particular is a DECISION here (Luiz): Anima ships as its own download, and nesting it would
     * mean a player running two consumers carries two copies and lets Loader choose.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "dev/luizloyola/" + MOD_ID + "/", "assets/" + MOD_ID + "/", "data/" + MOD_ID + "/",
            "META-INF/");

    /** Top-level files that belong in the jar by name. */
    private static final List<String> ALLOWED_FILES = List.of(
            "fabric.mod.json", MOD_ID + ".mixins.json", MOD_ID + ".accesswidener", MOD_ID + ".ct",
            "LICENSE", "TRADEMARKS.md");

    private static final ModJar JAR = ModJar.fromSystemProperty("anima.jar");
    private static final JsonObject METADATA =
            JsonParser.parseString(JAR.text("fabric.mod.json")).getAsJsonObject();

    @Test
    @DisplayName("the licence and the trademark notice travel with the jar")
    void legalTextIsPackaged() {
        assertTrue(JAR.has("LICENSE"), JAR.name() + " ships no LICENSE");
        assertTrue(JAR.has("TRADEMARKS.md"), JAR.name() + " ships no TRADEMARKS.md — the licences "
                + "deliberately say nothing about the name, so the jar would otherwise imply the "
                + "name came with the code");
        assertTrue(JAR.text("LICENSE").startsWith(LICENCE_HEADING),
                "LICENSE does not begin \"" + LICENCE_HEADING + "\", but fabric.mod.json declares "
                        + LICENCE + " — one of the two is wrong");
        assertEquals(LICENCE, METADATA.get("license").getAsString(),
                "fabric.mod.json declares a licence this mod does not ship");
    }

    @Test
    @DisplayName("the metadata names this mod, at the version that was just built")
    void metadataMatchesTheBuild() {
        assertEquals(MOD_ID, METADATA.get("id").getAsString());
        assertEquals(System.getProperty("anima.version"), METADATA.get("version").getAsString(),
                "the version in the jar is not the version Gradle built — processResources did not "
                        + "expand it, or the two came from different runs");
    }

    @Test
    @DisplayName("every mixin the config names is actually in the jar")
    void mixinTargetsArePackaged() {
        String config = MOD_ID + ".mixins.json";
        assertTrue(METADATA.getAsJsonArray("mixins").asList().stream()
                        .anyMatch(e -> e.getAsString().equals(config)),
                "fabric.mod.json does not list " + config + ", so none of the mixins apply at all");
        assertTrue(JAR.has(config), config + " is named by fabric.mod.json but is not in the jar");

        JsonObject mixins = JsonParser.parseString(JAR.text(config)).getAsJsonObject();
        String pkg = mixins.get("package").getAsString().replace('.', '/');
        List<String> missing = new ArrayList<>();
        // A mixin can be listed under any of the three side-specific keys; a class missing from
        // any of them is a config that fails at load, and `required: true` makes that fatal.
        for (String side : List.of("mixins", "client", "server")) {
            if (!mixins.has(side)) {
                continue;
            }
            for (var entry : mixins.getAsJsonArray(side)) {
                String clazz = pkg + "/" + entry.getAsString().replace('.', '/') + ".class";
                if (!JAR.has(clazz)) {
                    missing.add(side + ": " + entry.getAsString() + " (looked for " + clazz + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), config + " names classes the jar does not carry — the mod "
                + "dies at load, and it dies for every player at once: " + missing);
    }

    @Test
    @DisplayName("nothing rode along that nobody put there")
    void theJarCarriesOnlyItsOwnFiles() {
        List<String> strays = JAR.entriesOutside(ALLOWED_PREFIXES).stream()
                .filter(e -> !ALLOWED_FILES.contains(e))
                // No node ships a refmap today (26.1+ is unobfuscated; the Mojang-mapped nodes do
                // not emit one, checked against the remapped 1.21.11 jar). Allowed by shape anyway:
                // a refmap is a Loom/mixin-config property and would arrive without a commit.
                .filter(e -> !e.matches(".*refmap.*\\.json"))
                .toList();
        assertTrue(strays.isEmpty(), () -> ModJar.class.getSimpleName() + ": " + JAR.name()
                + " carries " + strays.size() + " unexpected entr(y/ies) — a shaded dependency, a "
                + "stray asset, or a nested jar (which this repo deliberately does not use): "
                + strays);
    }

    @Test
    @DisplayName("the library names no consumer")
    void animaShipsNothingOfAutarkias() {
        List<String> theirs = JAR.entries().stream()
                .filter(e -> e.contains("autarkia"))
                .toList();
        assertTrue(theirs.isEmpty(), "Anima must be usable by any consumer and must never name a "
                + "Person — a Fidelia user downloading this jar would be carrying Autarkia: " + theirs);
    }
}
