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
     * anyone deciding it should — a shaded dependency or a stray asset.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "dev/luizloyola/" + MOD_ID + "/", "assets/" + MOD_ID + "/", "data/" + MOD_ID + "/",
            "META-INF/", "licenses/");

    /** Top-level files that belong in the jar by name. */
    private static final List<String> ALLOWED_FILES = List.of(
            "fabric.mod.json", MOD_ID + ".mixins.json", MOD_ID + ".accesswidener", MOD_ID + ".ct",
            "LICENSE", "TRADEMARKS.md", "THIRD-PARTY.md");

    /**
     * The nested jars this mod is allowed to carry, BY NAME rather than under a blanket
     * {@code META-INF/}: a dependency that starts nesting itself after a version bump arrives
     * without a commit mentioning it, carrying a licence nobody read.
     *
     * <p>Jar-in-jar is a DECISION (Luiz) about our mods — Anima is its own download, never nested
     * into Autarkia, or a player running two consumers carries two copies and lets Loader choose.
     * A third-party library was never that rule's case: the alternative is every Anima user
     * hand-installing a parser, and Loader de-duplicates night-config.
     */
    private static final List<String> ALLOWED_NESTED_JARS = List.of(
            "META-INF/jars/core-" + nightConfig() + ".jar",
            "META-INF/jars/toml-" + nightConfig() + ".jar");

    /** night-config's version, handed in by the build so a bump stays a one-line edit. */
    private static String nightConfig() {
        return System.getProperty("anima.night_config.version");
    }

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
    @DisplayName("the nested library's terms travel with it")
    void nestedLicenceTextIsPackaged() {
        assertTrue(JAR.has("THIRD-PARTY.md"), JAR.name() + " nests a third-party library but "
                + "ships no THIRD-PARTY.md — night-config's own jars carry no licence text, so "
                + "without this nobody holding this jar can read the terms of the code inside it");
        for (String text : List.of("licenses/LGPL-3.0.txt", "licenses/GPL-3.0.txt")) {
            assertTrue(JAR.has(text), text + " is missing — LGPL-3.0 is not conveyed by naming it");
        }
        assertTrue(JAR.text("licenses/LGPL-3.0.txt")
                        .startsWith("                   GNU LESSER GENERAL PUBLIC LICENSE"),
                "licenses/LGPL-3.0.txt is not the verbatim LGPL — the file night-config is "
                        + "actually licensed under is the one that has to be in here");
    }

    @Test
    @DisplayName("the jar nests exactly the libraries it says it does")
    void nestsOnlyTheDeclaredLibraries() {
        List<String> nested = JAR.entries().stream()
                .filter(e -> e.startsWith("META-INF/jars/"))
                .filter(e -> e.endsWith(".jar"))
                .sorted()
                .toList();
        assertEquals(ALLOWED_NESTED_JARS.stream().sorted().toList(), nested,
                "the nested jars are not the ones this mod declares. Anima nests night-config and "
                        + "nothing else, and each nested jar carries a licence somebody has to "
                        + "have read: a new one here needs a row in THIRD-PARTY.md and its text "
                        + "in licenses/ before it ships");
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
                + " carries " + strays.size() + " unexpected entr(y/ies) — a shaded dependency or "
                + "a stray asset. Nested jars are covered separately and by name, see "
                + "nestsOnlyTheDeclaredLibraries(): " + strays);
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
