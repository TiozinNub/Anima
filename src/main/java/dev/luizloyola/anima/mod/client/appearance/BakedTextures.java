package dev.luizloyola.anima.mod.client.appearance;

import dev.luizloyola.anima.compat.client.appearance.NativeImages;
import dev.luizloyola.anima.core.appearance.Canonical;
import dev.luizloyola.anima.core.appearance.Compositor;
import dev.luizloyola.anima.core.appearance.Recipe;
import dev.luizloyola.anima.mod.AnimaMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Where a {@link Recipe} becomes something an {@code Identifier} can name: bake the pixels, wrap
 * them in a {@code DynamicTexture}, register it under the recipe's hash, hand back the name. The
 * composed look then works anywhere an id is accepted, with no consumer aware it was synthesised.
 *
 * <h2>No mixin</h2>
 * {@code TextureManager.getTexture} consults its own registered map <b>before</b> the resource
 * manager, and {@code register} is public — so registering there leaves no mixin target to chase
 * across versions, and it stays ordinary vanilla API under Sinytra Connector.
 *
 * <h2>Who owns the native memory</h2>
 * A leaked {@code NativeImage} is <b>native</b> memory: it never appears in a heap profile. So
 * ownership is explicit and counted — textures are keyed by {@link Recipe#hash()} so two agents who
 * look the same share one, each entry counts its holders, a {@link Handle} is one holder, and the
 * last to let go closes the image through {@code TextureManager.release}. {@link #clear()} is the
 * blunt instrument where holders cannot be trusted to report (a resource reload, a disconnect), and
 * {@link #generation} is how the handles find out.
 *
 * <h2>Hot swap</h2>
 * State is reached through lazy accessors ({@link #live()}, {@link #sprites()}) because a static
 * field <em>added</em> by a class redefinition arrives null on a class with live instances; a swap
 * then rebuilds it on the next frame instead of dying mid-render.
 */
@Environment(EnvType.CLIENT)
public final class BakedTextures {
    private BakedTextures() {}

    /** Registered textures, by recipe hash. Lazy — see the hot-swap note on the class. */
    private static @Nullable Map<Long, Entry> live;

    /** The decoded art every bake reads from. Lazy for the same reason. */
    private static @Nullable ResourceSprites sprites;

    /**
     * Bumped by {@link #clear()}. A {@link Handle} carries the generation it acquired under, which
     * is how it learns its texture was released underneath it — there is no registry of handles.
     */
    private static int generation;

    /** One registered texture and the number of handles keeping it alive. */
    private static final class Entry {
        final Identifier id;
        /** False when the bake drew nothing and {@link #id} is the missing texture: there is a name
         *  to hand out but nothing of ours to release. Cached all the same, so a recipe naming art
         *  nobody ships is not re-baked on every frame of every agent wearing it. */
        final boolean registered;
        int holders;

        Entry(Identifier id, boolean registered) {
            this.id = id;
            this.registered = registered;
        }
    }

    /** A fresh holder, for one agent. The caller must {@link Handle#dispose()} it. */
    public static Handle handle() {
        return new Handle();
    }

    /**
     * One agent's live texture, and the only thing that acquires or releases a reference. Owned by
     * a client-side object that outlives a frame (a client entity, a screen), and disposed when
     * that object goes away. It remembers what it already holds, so a render method calling sixty
     * times a second does not count as sixty holders.
     */
    @Environment(EnvType.CLIENT)
    public static final class Handle {
        private @Nullable Recipe lastRecipe;
        private @Nullable Identifier id;
        private long held;
        private int generation;

        private Handle() {}

        /**
         * The texture id for this recipe, acquiring one and letting the previous one go if the
         * look has moved. Safe to call every frame.
         *
         * <p>The reference-equality check comes first because hashing builds the recipe's canonical
         * string, and a consumer that memoises its recipe (as a Person does, against the synced
         * appearance) gets the same object back until the appearance changes.
         */
        public Identifier textureFor(Recipe recipe) {
            if (this.generation != BakedTextures.generation) {
                // Everything was released underneath us. Forget without releasing — the entry we
                // were counted in no longer exists, and a release now would decrement a fresh entry
                // that happens to share our hash.
                this.lastRecipe = null;
                this.id = null;
                this.generation = BakedTextures.generation;
            } else if (recipe == this.lastRecipe && this.id != null) {
                return this.id;
            }

            long hash = recipe.hash();
            if (this.id != null && this.held == hash) {
                this.lastRecipe = recipe;
                return this.id;
            }

            // Acquire before releasing, never the other way round: an agent who changes look and
            // changes back, or two agents trading looks, would otherwise drop a shared texture to
            // zero holders and re-bake the picture that was already on the GPU.
            Entry acquired = acquire(recipe, hash);
            releaseHeld();
            this.held = hash;
            this.id = acquired.id;
            this.lastRecipe = recipe;
            return acquired.id;
        }

        /** Give up this handle's reference. Idempotent, so an entity removed twice costs nothing. */
        public void dispose() {
            releaseHeld();
            this.lastRecipe = null;
            this.id = null;
        }

        private void releaseHeld() {
            if (this.id != null && this.generation == BakedTextures.generation) {
                BakedTextures.release(this.held);
            }
            this.id = null;
        }
    }

    /**
     * Drop every baked texture and every decoded source sprite.
     *
     * <p>Called on a resource reload — the source art may have been replaced by a newly loaded
     * pack, and a plain {@code DynamicTexture} is not a {@code ReloadableTexture}, so vanilla
     * leaves ours showing the previous pack's pixels. Also on disconnect, where the agents holding
     * these handles are gone but nothing guarantees each was told.
     */
    public static void clear() {
        Map<Long, Entry> registered = live();
        for (Entry entry : registered.values()) {
            if (entry.registered) {
                Minecraft.getInstance().getTextureManager().release(entry.id);
            }
        }
        // Logged even when it releases nothing: zero means every handle had already given its
        // texture back through the ordinary per-agent path, where silence would mean the sweep
        // never ran, and the failure between them leaves no other trace — a leaked NativeImage is
        // native memory and never shows in a heap dump. Fires at most twice a session.
        AnimaMod.LOGGER.info("appearance: released {} baked texture(s) ({} source sprite(s) dropped)",
                registered.size(), sprites().size());
        registered.clear();
        idle().clear();
        sprites().clear();
        generation++;
    }

    /** How many baked textures are live. Diagnostics — this is the number that must not creep. */
    public static int liveCount() {
        return live().size();
    }

    private static Entry acquire(Recipe recipe, long hash) {
        Entry entry = live().get(hash);
        if (entry == null) {
            entry = bake(recipe, hash);
            live().put(hash, entry);
            AnimaMod.LOGGER.debug("appearance: baked {} ({} live)", entry.id, live().size());
        }
        // Claiming one back out of the park is the point of it: an eye reopening finds the texture
        // it had a moment ago rather than compositing it again.
        idle().remove(hash);
        entry.holders++;
        return entry;
    }

    /**
     * Textures nobody is holding any more, youngest last, kept against the near future.
     *
     * <p>Freeing the instant the last holder lets go is <b>wrong for a face</b>: an agent blinking
     * alternates two textures every few seconds, so a sole wearer closing their eyes drops the
     * open-eyed texture to zero holders and re-composites and re-uploads it 150 milliseconds later.
     * Measured on eighteen settlers: 358 bakes for 28 distinct textures.
     *
     * <p>So an unheld texture is <em>parked</em> rather than freed, and evicted only once the park
     * is full — the cap is the memory bound.
     */
    private static @Nullable LinkedHashSet<Long> idle;

    /**
     * How many unheld textures to keep. At 16 KB of native memory each this is about a megabyte —
     * against a single vanilla block atlas at four — and it is comfortably more than the two-per-face
     * a settlement's worth of blinking needs.
     */
    private static final int IDLE_CAPACITY = 64;

    private static void release(long hash) {
        Entry entry = live().get(hash);
        if (entry == null) {
            return;
        }
        if (--entry.holders <= 0) {
            idle().add(hash);
            evictWhileOverCapacity();
        }
    }

    /** Free parked textures oldest-first until the park is inside its cap. */
    private static void evictWhileOverCapacity() {
        Iterator<Long> oldest = idle().iterator();
        while (idle().size() > IDLE_CAPACITY && oldest.hasNext()) {
            Entry evicted = live().remove(oldest.next());
            oldest.remove();
            if (evicted != null && evicted.registered) {
                // release() removes the texture from the manager and closes it. That is what frees
                // the NativeImage the DynamicTexture took ownership of.
                Minecraft.getInstance().getTextureManager().release(evicted.id);
            }
        }
    }

    private static Entry bake(Recipe recipe, long hash) {
        Compositor.Bake baked = Compositor.bake(recipe, sprites());
        if (drewNothing(baked)) {
            // A transparent person looks like a bug in the entity rather than in the art, and is
            // invisible in a screenshot — so hand out vanilla's missing texture instead.
            AnimaMod.LOGGER.warn("appearance: nothing drew for recipe {} — {}",
                    Canonical.hex(hash), Canonical.stream(recipe.all()));
            return new Entry(MissingTextureAtlasSprite.getLocation(), false);
        }
        Identifier id = idFor(hash);
        // The DynamicTexture takes ownership of the image and closes it when the manager releases
        // the texture.
        DynamicTexture texture = new DynamicTexture(() -> id.toString(), NativeImages.imageOf(baked.image()));
        Minecraft.getInstance().getTextureManager().register(id, texture);
        dumpIfAsked(id, texture);
        return new Entry(id, true);
    }

    /**
     * Write every newly baked texture to disk, when {@code -Danima.appearance.dump=<dir>} asks for
     * it.
     *
     * <p>A composited texture is the one output of this mod that cannot be read out of a log or a
     * command — it exists only on the GPU and in native memory. The acceptance test is that the
     * bake changed nothing, and a PNG is what makes that checkable against the source art.
     */
    private static void dumpIfAsked(Identifier id, DynamicTexture texture) {
        String directory = System.getProperty("anima.appearance.dump");
        if (directory == null) {
            return;
        }
        try {
            Path target = Path.of(directory);
            Files.createDirectories(target);
            texture.dumpContents(id, target);
            AnimaMod.LOGGER.info("appearance: dumped {} to {}", id, target);
        } catch (IOException unwritable) {
            AnimaMod.LOGGER.warn("appearance: could not dump {}: {}", id, unwritable.getMessage());
        }
    }

    /** The texture id a hash gets. Hex, because a recipe's own spelling carries colours and slashes
     *  and an {@code Identifier} path admits neither. */
    private static Identifier idFor(long hash) {
        return Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "baked/" + Canonical.hex(hash));
    }

    /** True when not one part of the recipe resolved to art — the whole canvas is still empty. */
    private static boolean drewNothing(Compositor.Bake baked) {
        for (int owner : baked.partIds()) {
            if (owner != Compositor.NOBODY) {
                return false;
            }
        }
        return true;
    }

    private static LinkedHashSet<Long> idle() {
        if (idle == null) {
            idle = new LinkedHashSet<>();
        }
        return idle;
    }

    private static Map<Long, Entry> live() {
        if (live == null) {
            live = new HashMap<>();
        }
        return live;
    }

    private static ResourceSprites sprites() {
        if (sprites == null) {
            sprites = new ResourceSprites();
        }
        return sprites;
    }
}
