package dev.luizloyola.anima.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.arch.SourceTree.JavaSource;
import dev.luizloyola.anima.arch.SourceTree.Line;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the scanner {@code ArchitectureTest} rests on, against a hand-built tree. A "this must not
 * appear" rule passes as happily on a broken scanner as on a clean codebase, so what matters is
 * what the scanner must not see: core/ javadoc names {@code net.minecraft} constantly, and a
 * checker that read prose would fail files for keeping their word.
 */
class SourceTreeTest {

    @Test
    @DisplayName("a package named only in prose is not a dependency")
    void commentsAreNotCode(@TempDir Path dir) {
        JavaSource file = one(dir, "Pure.java", """
                package p;
                /** Kept a bare core record with no net.minecraft dependency — compat translates. */
                // net.minecraft.world.level.Level would belong behind a facade
                class Pure {
                    /* net.minecraft again, in a block comment */
                    int x = 1;
                }
                """);
        assertEquals(List.of(), file.mentions("net.minecraft."));
    }

    @Test
    @DisplayName("an import and a fully-qualified use are both found, with their line numbers")
    void importsAndQualifiedUsesAreFound(@TempDir Path dir) {
        JavaSource file = one(dir, "Leaky.java", """
                package p;
                import net.minecraft.world.level.Level;
                class Leaky {
                    Object o = new net.minecraft.world.phys.Vec3(0, 0, 0);
                }
                """);
        assertEquals(List.of(2, 4), file.mentions("net.minecraft.").stream().map(Line::number).toList());
        assertTrue(file.mentions("net.minecraft.").get(0).text().startsWith("import net.minecraft"),
                "a violation must report what was written, not the blanked-out form");
    }

    @Test
    @DisplayName("string literals and text blocks hide nothing and reveal nothing")
    void literalsAreNotCode(@TempDir Path dir) {
        JavaSource file = one(dir, "Strings.java", """
                package p;
                class Strings {
                    String a = "net.minecraft.world.level.Level";
                    String b = "he said \\" net.minecraft. \\" and stopped";
                    char c = '"';
                    String d = \"""
                            net.minecraft. inside a text block
                            // and a line comment that is really just text
                            \""";
                    int end = 1;
                }
                """);
        assertEquals(List.of(), file.mentions("net.minecraft."),
                "a package name quoted as data is not a dependency on it");
        // A scanner that mishandles an escaped quote or a text block swallows the rest of the
        // file, and every rule then passes by seeing nothing.
        assertTrue(file.code().contains("int end = 1;"), "the scanner lost the tail of the file");
    }

    @Test
    @DisplayName("Stonecutter directives are found where they can appear, and only there")
    void directivesAreRecognised(@TempDir Path dir) {
        JavaSource file = one(dir, "Compat.java", """
                package p;
                /**
                 * Preprocessor comments ({@code //? if >=26.1}) are allowed only in this package.
                 */
                class Compat {
                    void f() {
                        //? if >=26.1 {
                        int a = 1;
                        //?} else {
                        /*int a = 2;
                        *///?}
                    }
                }
                """);
        assertEquals(List.of(7, 9, 11), file.directives().stream().map(Line::number).toList(),
                "the javadoc line QUOTES a directive and must not count as one");
    }

    @Test
    @DisplayName("a package that has moved fails loudly instead of guarding nothing")
    void anEmptyPackageIsAnError(@TempDir Path dir) {
        one(dir, "Only.java", "package p;\nclass Only {}\n");
        SourceTree tree = SourceTree.rootedAt(dir);
        assertThrows(IllegalStateException.class, () -> tree.inPackage("dev.luizloyola.anima.core"));
    }

    private static JavaSource one(Path dir, String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<JavaSource> files = SourceTree.rootedAt(dir).all();
        return files.stream().filter(f -> f.path().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("did not scan " + name));
    }
}
