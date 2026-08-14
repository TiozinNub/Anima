package dev.luizloyola.anima.core.inv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The literal ({@code anyOf}) side of {@link ItemSpec} — the contract a persisted plan leans on:
 * same content, same name, same canonical instance, on any JVM, in any order.
 */
class ItemSpecTest {

    @Test
    void sameContentInAnyOrderIsTheSameCanonicalSpec() {
        ItemSpec ab = ItemSpec.anyOf(new LinkedHashSet<>(List.of("mod:a", "mod:b")));
        ItemSpec ba = ItemSpec.anyOf(new LinkedHashSet<>(List.of("mod:b", "mod:a")));
        assertSame(ab, ba, "content is the identity; insertion order is noise");
        assertTrue(ab.matches("mod:a"));
        assertTrue(ab.matches("mod:b"));
    }

    @Test
    void differentContentGetsADifferentName() {
        assertNotEquals(ItemSpec.anyOf(Set.of("mod:a", "mod:b")).name(),
                ItemSpec.anyOf(Set.of("mod:a", "mod:c")).name(),
                "the hash tail keeps two families with the same head apart");
    }

    @Test
    void aSingletonReadsAsThePlainItem() {
        assertEquals("stick", ItemSpec.anyOf(Set.of("minecraft:stick")).name(),
                "journal lines say 'obtain stick', not a hash");
    }

    @Test
    void literalIdsHandBackTheContentAndByNameFindsTheSpec() {
        ItemSpec spec = ItemSpec.anyOf(Set.of("mod:x", "mod:y"));
        assertEquals(Set.of("mod:x", "mod:y"), ItemSpec.literalIds(spec).orElseThrow(),
                "the codec writes the content back down from here");
        assertSame(spec, ItemSpec.byName(spec.name()).orElseThrow());
    }

    @Test
    void declaredSpecsAreNotLiterals() {
        ItemSpec declared = ItemSpec.register(
                new ItemSpec("spec-test-declared", id -> id.endsWith("_declared")));
        assertTrue(ItemSpec.literalIds(declared).isEmpty(),
                "a lambda spec persists by NAME; only anyOf content is writable");
    }
}
