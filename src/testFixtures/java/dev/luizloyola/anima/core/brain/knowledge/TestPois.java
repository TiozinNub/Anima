package dev.luizloyola.anima.core.brain.knowledge;

/**
 * Sample POI kinds for tests of the knowledge machinery: the suite needs <em>some</em> vocabulary
 * for the store, the claims and the merge radius, but must not borrow a consumer's — the library
 * has no opinion about trees. Also the smallest worked example of {@link PoiKind#register}.
 */
public final class TestPois {

    /** A small-merge kind, standing in for anything clustered and countable. */
    public static final PoiKind TREE = PoiKind.register("test_tree", 1, " logs");

    /** A wide-merge kind, standing in for anything large and re-discoverable from many sides. */
    public static final PoiKind WATER = PoiKind.register("test_water", 8, "");

    private TestPois() {
    }
}
