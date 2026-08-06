package dev.luizloyola.anima.core.appearance.catalog;

import dev.luizloyola.anima.core.appearance.ColorOp;
import dev.luizloyola.anima.core.appearance.Part;
import dev.luizloyola.anima.core.appearance.RampSpec;
import dev.luizloyola.anima.core.appearance.Recipe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Everything a consumer authored about how its agents look: the canvas, the anchors, the ramp
 * curves, the colour ladders and the slots.
 *
 * <p>Anima owns the <em>shape</em> and nothing about the contents — an anchor is "a named region of
 * a canvas", a slot "a placed sprite chosen by parameters" — so what lands in the file is the
 * consumer's vocabulary, shipped as data a pack can extend without touching Java.
 *
 * <p>{@link #compose} is the whole point: parameters and bindings in, a bakeable {@link Recipe}
 * out, and everything downstream works off that one product.
 */
public record Catalog(int canvasWidth, int canvasHeight,
                      Map<String, Anchor> anchors,
                      Map<String, RampSpec> ramps,
                      Map<String, LadderSpec> ladders,
                      List<SlotSpec> slots) {

    public Catalog {
        anchors = Map.copyOf(Objects.requireNonNull(anchors, "anchors"));
        ramps = Map.copyOf(Objects.requireNonNull(ramps, "ramps"));
        ladders = Map.copyOf(Objects.requireNonNull(ladders, "ladders"));
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("a canvas of " + canvasWidth + "x" + canvasHeight);
        }
    }

    public @Nullable Anchor anchor(String name) {
        return anchors.get(name);
    }

    public @Nullable SlotSpec slot(String name) {
        return slots.stream().filter(slot -> slot.name().equals(name)).findFirst().orElse(null);
    }

    /** An entry of a named ladder, wrapping so an out-of-range index cannot break a bake. */
    public int ladder(String name, int index) {
        LadderSpec spec = ladders.get(name);
        return spec == null ? LadderSpec.FALLBACK : spec.color(index);
    }

    /**
     * Build the recipe for one agent.
     *
     * @param params   what is currently true of them — mood, blink, style names, anything a
     *                 selector reads. Strings, because a selector compares them as written.
     * @param bindings their colours by name ({@code SKIN}, {@code HAIR}, {@code EYE}), each a
     *                 24-bit RGB.
     *
     * <p>A slot whose selector matches nothing (grime, blood) or whose anchor does not exist is
     * skipped rather than fatal — a missing anchor must cost one layer, not the agent. The editor's
     * validation pass reports both.
     */
    public Recipe compose(Map<String, String> params, Map<String, Integer> bindings) {
        return compose(params, bindings, texture -> true);
    }

    /**
     * As {@link #compose(Map, Map)}, but able to answer "specific if it exists, shared otherwise".
     *
     * <p>A rule may offer several textures, best first — {@code shirt_slim} then {@code shirt} —
     * and only the caller knows which have been drawn, so {@code exists} decides.
     *
     * <p>When nothing exists the <b>last</b> candidate wins: the author's general case, and the
     * more useful name in a missing-texture report. The part is skipped at bake time either way.
     */
    public Recipe compose(Map<String, String> params, Map<String, Integer> bindings,
                          Predicate<String> exists) {
        List<Part> statics = new ArrayList<>();
        List<Part> dynamics = new ArrayList<>();
        for (SlotSpec slot : slots) {
            Anchor anchor = anchors.get(slot.anchor());
            if (anchor == null) {
                continue;
            }
            String texture = chosen(slot, params, exists);
            if (texture == null) {
                continue;
            }
            List<ColorOp> ops = OpSpec.resolveAll(slot.ops(), bindings, ramps);
            Part part = new Part(texture,
                    anchor.x() + slot.offsetX(), anchor.y() + slot.offsetY(),
                    slot.width(), slot.height(), ops);
            (slot.dynamic() ? dynamics : statics).add(part);
        }
        return new Recipe(canvasWidth, canvasHeight, statics, dynamics);
    }

    /**
     * The texture a slot wears, or {@code null} if it wears none.
     *
     * <p>Two ways to wear none: the selector matches nothing (ordinary for blood or grime), or
     * every candidate still carries an <b>unfilled placeholder</b> — nobody has chosen a member of
     * that family, which is a choice not made rather than a file gone missing.
     */
    private @Nullable String chosen(SlotSpec slot, Map<String, String> params,
                                    Predicate<String> exists) {
        List<String> candidates = slot.selector().candidates(params);
        String fallback = null;
        for (String candidate : candidates) {
            if (candidate.indexOf('{') >= 0) {
                continue;
            }
            if (exists.test(candidate)) {
                return candidate;
            }
            fallback = candidate;
        }
        return fallback;
    }

    /** Whether a slot is drawing at all — the same question {@link #chosen} answers. */
    public boolean draws(SlotSpec slot, Map<String, String> params) {
        return chosen(slot, params, texture -> true) != null;
    }

    /**
     * Which slot produced each part of a composed recipe, in {@link Recipe#all()} order — how the
     * editor turns "the part under the cursor" into "the slot to edit".
     *
     * <p>Recomputed rather than carried on {@link Part}: two agents in identical clothes share a
     * texture only because their parts carry no identity beyond their pixels.
     */
    public List<String> slotNamesFor(Map<String, String> params) {
        List<String> staticNames = new ArrayList<>();
        List<String> dynamicNames = new ArrayList<>();
        for (SlotSpec slot : slots) {
            if (anchors.containsKey(slot.anchor()) && draws(slot, params)) {
                (slot.dynamic() ? dynamicNames : staticNames).add(slot.name());
            }
        }
        staticNames.addAll(dynamicNames);
        return List.copyOf(staticNames);
    }

    /** Default bindings taken from each ladder's first entry — enough to draw before anyone has
     *  rolled a genotype. That is what the editor opens with. */
    public Map<String, Integer> defaultBindings() {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, LadderSpec> ladder : ladders.entrySet()) {
            if (ladder.getValue().size() > 0) {
                bindings.put(ladder.getKey().toUpperCase(java.util.Locale.ROOT), ladder.getValue().color(0));
            }
        }
        return bindings;
    }
}
