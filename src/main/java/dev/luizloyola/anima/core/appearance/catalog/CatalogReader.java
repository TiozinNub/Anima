package dev.luizloyola.anima.core.appearance.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luizloyola.anima.core.appearance.RampSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a {@link Catalog} from JSON — the one parser, shared by the game and the appearance
 * editor, because two would drift and the editor's value is showing what the game will draw.
 *
 * <p>Gson's tree API rather than its reflective binder: the shapes are irregular (a tint is a
 * colour <em>or</em> a binding, a slot's size defaults to its anchor's) and a hand-written reader
 * can say <em>which</em> slot was wrong.
 *
 * <p><b>A malformed catalog throws</b>, unlike everything else here, which degrades: a catalog is
 * <em>authored</em>, so a typo is fixable the moment somebody is told. The editor shows the throw
 * rather than swapping in a half-read catalog.
 */
public final class CatalogReader {
    private CatalogReader() {}

    public static Catalog read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray canvas = array(root, "canvas", 2);

        Map<String, Anchor> anchors = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object(root, "anchors").entrySet()) {
            JsonArray box = entry.getValue().getAsJsonArray();
            require(box.size() == 4, "anchor '" + entry.getKey() + "' must be [x, y, width, height]");
            anchors.put(entry.getKey(), new Anchor(entry.getKey(),
                    box.get(0).getAsInt(), box.get(1).getAsInt(),
                    box.get(2).getAsInt(), box.get(3).getAsInt()));
        }

        Map<String, RampSpec> ramps = new LinkedHashMap<>();
        if (root.has("ramps")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("ramps").entrySet()) {
                ramps.put(entry.getKey(), ramp(entry.getKey(), entry.getValue()));
            }
        }

        Map<String, LadderSpec> ladders = new LinkedHashMap<>();
        if (root.has("ladders")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("ladders").entrySet()) {
                ladders.put(entry.getKey(), ladder(entry.getKey(), entry.getValue()));
            }
        }

        Map<String, ChoiceWeights> odds = new LinkedHashMap<>();
        if (root.has("odds")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("odds").entrySet()) {
                odds.put(entry.getKey(), odds(entry.getKey(), entry.getValue().getAsJsonObject()));
            }
        }

        List<SlotSpec> slots = new ArrayList<>();
        for (JsonElement element : array(root, "slots", -1)) {
            slots.add(slot(element.getAsJsonObject(), anchors));
        }

        return new Catalog(canvas.get(0).getAsInt(), canvas.get(1).getAsInt(),
                anchors, ramps, ladders, odds, slots);
    }

    /**
     * How likely each member of one family is.
     *
     * <p>A map by NAME, not a list: a family's members are discovered by walking a folder, so a
     * parallel array would go out of step the first time somebody drew a new PNG. {@code "*"} sets
     * what an unnamed member is worth.
     */
    private static ChoiceWeights odds(String parameter, JsonObject object) {
        Map<String, Integer> byValue = new LinkedHashMap<>();
        int fallback = 1;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            int weight = entry.getValue().getAsInt();
            require(weight >= 0, "odds for '" + parameter + "." + entry.getKey()
                    + "' may not be negative: " + weight);
            if (ChoiceWeights.DEFAULT_KEY.equals(entry.getKey())) {
                fallback = weight;
            } else {
                byValue.put(entry.getKey(), weight);
            }
        }
        return new ChoiceWeights(byValue, fallback);
    }

    /**
     * One ladder, in either spelling.
     *
     * <p>A bare array is the colours alone, drawn triangularly — see {@link LadderSpec}. An object
     * may add {@code weights}, one per colour, replacing "the middle of the list is commoner" with
     * a statement of how common each colour actually is.
     */
    private static LadderSpec ladder(String name, JsonElement element) {
        if (!element.isJsonObject()) {
            return LadderSpec.of(colours(element.getAsJsonArray()));
        }
        JsonObject object = element.getAsJsonObject();
        require(object.has("colors"), "ladder '" + name + "' has no colors");
        List<Integer> colours = colours(object.getAsJsonArray("colors"));
        List<Integer> weights = new ArrayList<>();
        if (object.has("weights")) {
            for (JsonElement weight : object.getAsJsonArray("weights")) {
                weights.add(weight.getAsInt());
            }
            // Checked here rather than left to the record, so the message names the ladder.
            require(weights.size() == colours.size(), "ladder '" + name + "' has " + colours.size()
                    + " colour(s) but " + weights.size() + " weight(s) — there must be one each");
        }
        return new LadderSpec(colours, weights);
    }

    private static List<Integer> colours(JsonArray array) {
        List<Integer> colours = new ArrayList<>();
        for (JsonElement colour : array) {
            colours.add(rgb(colour.getAsString()));
        }
        return List.copyOf(colours);
    }

    /**
     * One ramp, in either spelling.
     *
     * <p>A bare array is the shades alone, drawn in {@link dev.luizloyola.anima.core.appearance.Shades}'
     * reserved encoding. An object may also carry {@code keys}: the authored colours those shades
     * replace, which lets a layer be drawn in real, visible colour. Both forms are permanent.
     */
    private static RampSpec ramp(String name, JsonElement element) {
        JsonArray shadeArray;
        List<Integer> keys = new ArrayList<>();
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            require(object.has("shades"), "ramp '" + name + "' has no shades");
            shadeArray = object.getAsJsonArray("shades");
            if (object.has("keys")) {
                for (JsonElement key : object.getAsJsonArray("keys")) {
                    keys.add(rgb(key.getAsString()));
                }
            }
        } else {
            shadeArray = element.getAsJsonArray();
        }

        List<RampSpec.Shade> shades = new ArrayList<>();
        for (JsonElement shade : shadeArray) {
            JsonArray triple = shade.getAsJsonArray();
            require(triple.size() == 3, "ramp '" + name + "' shades are [hue10, sat1000, val1000]");
            shades.add(new RampSpec.Shade(
                    triple.get(0).getAsInt(), triple.get(1).getAsInt(), triple.get(2).getAsInt()));
        }
        return new RampSpec(name, shades, keys);
    }

    private static SlotSpec slot(JsonObject json, Map<String, Anchor> anchors) {
        String name = string(json, "name");
        String anchorName = string(json, "anchor");
        Anchor anchor = anchors.get(anchorName);
        require(anchor != null, "slot '" + name + "' names anchor '" + anchorName + "', which does not exist");

        int offsetX = 0;
        int offsetY = 0;
        if (json.has("offset")) {
            JsonArray offset = json.getAsJsonArray("offset");
            require(offset.size() == 2, "slot '" + name + "' offset must be [x, y]");
            offsetX = offset.get(0).getAsInt();
            offsetY = offset.get(1).getAsInt();
        }
        // A slot with no size is the whole of its anchor. That is what makes a body or a set of
        // clothes a slot with nothing in it but a name and an anchor.
        int width = anchor.width();
        int height = anchor.height();
        if (json.has("size")) {
            JsonArray size = json.getAsJsonArray("size");
            require(size.size() == 2, "slot '" + name + "' size must be [width, height]");
            width = size.get(0).getAsInt();
            height = size.get(1).getAsInt();
        }

        List<OpSpec> ops = new ArrayList<>();
        if (json.has("ops")) {
            for (JsonElement op : json.getAsJsonArray("ops")) {
                ops.add(op(op.getAsJsonObject(), name));
            }
        }

        List<Selector.Rule> rules = new ArrayList<>();
        for (JsonElement rule : array(json, "select", -1)) {
            JsonObject entry = rule.getAsJsonObject();
            Map<String, String> when = new LinkedHashMap<>();
            if (entry.has("when")) {
                for (Map.Entry<String, JsonElement> condition : entry.getAsJsonObject("when").entrySet()) {
                    when.put(condition.getKey(), condition.getValue().getAsString());
                }
            }
            // "texture" is one id, or several best-first: the model-specific one, then the shared
            // one it falls back to when nobody has drawn it.
            require(entry.has("texture"), "slot '" + name + "' has a rule with no texture");
            List<String> textures = new ArrayList<>();
            if (entry.get("texture").isJsonArray()) {
                entry.getAsJsonArray("texture").forEach(one -> textures.add(one.getAsString()));
                require(!textures.isEmpty(), "slot '" + name + "' has a rule with an empty texture list");
            } else {
                textures.add(entry.get("texture").getAsString());
            }
            rules.add(new Selector.Rule(when, textures));
        }

        boolean dynamic = json.has("dynamic") && json.get("dynamic").getAsBoolean();
        boolean optional = json.has("optional") && json.get("optional").getAsBoolean();
        return new SlotSpec(name, anchorName, offsetX, offsetY, width, height, dynamic, optional,
                ops, new Selector(rules));
    }

    private static OpSpec op(JsonObject json, String slot) {
        String type = string(json, "type").toLowerCase(Locale.ROOT);
        switch (type) {
            case "multiply":
                return new OpSpec.Multiply(tint(json, slot));
            case "hsv":
                return new OpSpec.Hsv(
                        json.has("hue") ? json.get("hue").getAsFloat() : 0.0F,
                        json.has("sat") ? json.get("sat").getAsFloat() : 1.0F,
                        json.has("val") ? json.get("val").getAsFloat() : 1.0F);
            case "palette": {
                List<OpSpec.Palette.Swap> swaps = new ArrayList<>();
                for (JsonElement swap : array(json, "swaps", -1)) {
                    JsonObject pair = swap.getAsJsonObject();
                    swaps.add(new OpSpec.Palette.Swap(rgb(string(pair, "from")), tint(pair, slot)));
                }
                return new OpSpec.Palette(swaps);
            }
            case "ramp":
                return new OpSpec.Ramp(tint(json, slot), string(json, "ramp"));
            case "retint":
                // "from" is optional: stated, it is one reference for every texture the slot can
                // wear; omitted, each texture is measured against its own main colour, which is
                // what lets a family hold art taken off different skins.
                return json.has("from")
                        ? new OpSpec.Retint(rgb(string(json, "from")), tint(json, slot))
                        : new OpSpec.Retint(tint(json, slot));
            default:
                throw new IllegalArgumentException("slot '" + slot + "' has an op of unknown type '" + type + "'");
        }
    }

    /** {@code bind} names a per-agent colour, {@code color}/{@code to} is the literal — and doubles
     *  as the fallback when both are present. */
    private static Tint tint(JsonObject json, String slot) {
        int literal = 0xFFFFFF;
        if (json.has("color")) {
            literal = rgb(json.get("color").getAsString());
        } else if (json.has("to")) {
            literal = rgb(json.get("to").getAsString());
        }
        if (json.has("bind")) {
            return Tint.bound(json.get("bind").getAsString(), literal);
        }
        require(json.has("color") || json.has("to"),
                "slot '" + slot + "' has a tint with neither 'bind' nor 'color'");
        return Tint.literal(literal);
    }

    /** A 24-bit colour, with or without a leading {@code #}. */
    static int rgb(String text) {
        String digits = text.startsWith("#") ? text.substring(1) : text;
        require(digits.length() == 6, "'" + text + "' is not a 6-digit hex colour");
        try {
            return Integer.parseInt(digits, 16) & 0xFFFFFF;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("'" + text + "' is not a hex colour", malformed);
        }
    }

    private static JsonArray array(JsonObject json, String field, int size) {
        require(json.has(field), "missing '" + field + "'");
        JsonArray array = json.getAsJsonArray(field);
        require(size < 0 || array.size() == size, "'" + field + "' must have " + size + " entries");
        return array;
    }

    private static JsonObject object(JsonObject json, String field) {
        require(json.has(field), "missing '" + field + "'");
        return json.getAsJsonObject(field);
    }

    private static String string(JsonObject json, String field) {
        require(json.has(field), "missing '" + field + "'");
        return json.get(field).getAsString();
    }

    private static void require(boolean condition, String complaint) {
        if (!condition) {
            throw new IllegalArgumentException(complaint);
        }
    }
}
