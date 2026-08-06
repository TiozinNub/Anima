package dev.luizloyola.anima;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.arch.SourceTree;
import dev.luizloyola.anima.arch.SourceTree.JavaSource;
import dev.luizloyola.anima.arch.SourceTree.Line;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces the layering CLAUDE.md describes, over Anima's own source. Anima's rules are the strict
 * ones — its {@code core/} is what Fidelia inherits sight unseen. Autarkia runs a near-duplicate
 * over its own tree, sharing the scanner ({@link SourceTree}) and differing only in the tables
 * below (its {@code core/} may name Anima's).
 *
 * <p>No ArchUnit: two of these rules are about text that no class file retains. See
 * {@link SourceTree}.
 */
class ArchitectureTest {

    /** Where the branch source lives — handed in by the {@code test} task, never guessed. */
    private static final String SOURCE_ROOT_PROPERTY = "anima.arch.sourceRoot";

    private static final String ROOT = "dev.luizloyola.anima";
    private static final String CORE = ROOT + ".core";

    /**
     * Everything {@code core/} may not name. The game and the loader are CLAUDE.md's wording; the
     * mixin, render and Mojang libraries arrive only with Minecraft on the classpath and would tie
     * a pure simulation to one version just as firmly.
     */
    private static final List<String> GAME_PACKAGES = List.of(
            "net.minecraft.", "net.fabricmc.", "com.mojang.", "org.spongepowered.", "org.lwjgl.");

    /** The layers {@code core/} sits below: compat is the version seam, mod is the wiring. */
    private static final List<String> OUTER_LAYERS =
            List.of(ROOT + ".compat.", ROOT + ".mod.", ROOT + ".mixin.");

    /**
     * Fabric's internals. A HARD constraint: Connector re-implements only the public surface on
     * NeoForge, so an {@code impl}/{@code mixin} import boots on Fabric and dies on Connector —
     * the loader nobody here runs by default, so a player finds it first.
     */
    private static final List<String> FABRIC_INTERNALS =
            List.of("net.fabricmc.fabric.impl.", "net.fabricmc.fabric.mixin.");

    private static final SourceTree TREE = SourceTree.fromSystemProperty(SOURCE_ROOT_PROPERTY);

    @Test
    @DisplayName("core/ never names Minecraft, the loader, or anything else that arrives with them")
    void coreIsPureSimulation() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.inPackage(CORE)) {
            for (String banned : GAME_PACKAGES) {
                for (Line line : file.mentions(banned)) {
                    violations.add(SourceTree.at(file, line));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "core/ is the version-independent half of the mod and must stay headless-testable: "
                        + "the type belongs behind a compat/ facade named for what the agent needs",
                violations));
    }

    @Test
    @DisplayName("core/ never names the layers above it")
    void coreDoesNotReachUpwards() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.inPackage(CORE)) {
            for (String layer : OUTER_LAYERS) {
                for (Line line : file.mentions(layer)) {
                    violations.add(SourceTree.at(file, line));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "a dependency from core/ into compat/, mod/ or mixin/ inverts the layering — core "
                        + "defines the interface, the outer layer implements it",
                violations));
    }

    @Test
    @DisplayName("Stonecutter directives live only in compat/ and mixin/")
    void versionSpecificCodeIsQuarantined() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.all()) {
            if (file.path().contains("/compat/") || file.path().contains("/mixin/")) {
                continue;
            }
            for (Line line : file.directives()) {
                violations.add(SourceTree.at(file, line));
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "a `//?` outside compat/ or mixin/ spreads the version matrix across the codebase: "
                        + "every file carrying one has to be re-read on every Minecraft update, "
                        + "which is precisely what quarantining them buys",
                violations));
    }

    @Test
    @DisplayName("nothing reaches into Fabric API's internals")
    void onlyPublicFabricApiIsUsed() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.all()) {
            for (String internal : FABRIC_INTERNALS) {
                for (Line line : file.mentions(internal)) {
                    violations.add(SourceTree.at(file, line));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "Fabric's impl/ and mixin/ packages have no Connector equivalent — find the public "
                        + "API that exposes the same thing, or do it with an ordinary Mixin",
                violations));
    }

    @Test
    @DisplayName("only Replies speaks to a command source")
    void everyCommandReplyGoesThroughReplies() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.all()) {
            if (file.path().endsWith("/mod/command/Replies.java")) {
                continue;
            }
            for (String method : List.of("sendSuccess", "sendFailure")) {
                for (Line line : file.mentions(method)) {
                    violations.add(SourceTree.at(file, line));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "Replies.send/fail is the one choke point that stamps a line with the agent it ran "
                        + "as ([as John] …), which is the only thing that makes an "
                        + "`execute as @e … run anima contacts` sweep readable",
                violations));
    }
}
