plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.x"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    // Read off the NODE rather than the root project. In this single-mod repo the root form would
    // work too, but the node form is correct in both shapes — and this file was carried over from
    // a tree where the root had no `mod.version` at all.
    swaps["mod_version"] = "\"${node.project.property("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
    dependencies["fapi"] = node.project.property("deps.fabric_api") as String

    replacements {
        // ⚠ THE ONE RULE SHARED WITH AUTARKIA. Every other replacement below belongs to exactly
        // one mod (Autarkia's `CameraRenderState` and `classTweaker` rules are not here, and are
        // not missing). This one is used by both (29 sites across the two mods at the split), so
        // it is the only line in this file that has to be kept in step with another repository.
        // Currently INERT: every live node is >= 1.21.11. It becomes load-bearing again the day
        // 1.20.1/1.21.1 come back.
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }

        string(current.parsed < "26.1") {
            // Fabric networking-api-v1 renamed the payload-channel factories at v6 (bundled from 26.1);
            // pre-26.1 nodes ship v5, where the S2C play channel is still `playS2C()`. Only the factory
            // name changed — send/canSend/registerGlobalReceiver are identical. Source is 26.1 form.
            replace("PayloadTypeRegistry.clientboundPlay()", "PayloadTypeRegistry.playS2C()")

            // `DimensionDataStorage` was renamed `SavedDataStorage` at 26.1. Ordinary code never
            // names it — `level.getDataStorage().computeIfAbsent(...)` compiles either way — but the
            // store guard's accessor mixin has to say the class out loud, and a mixin target is a
            // string in a descriptor rather than something the compiler resolves. Same class, same
            // private `dataFolder` field, both sides.
            replace("SavedDataStorage", "DimensionDataStorage")

            // ...but the ACCESSOR keeps its name. A replacement rewrites a file's contents and never
            // its filename, so renaming the interface too produced a public `DimensionDataStorage-
            // Accessor` sitting in `SavedDataStorageAccessor.java` — which javac rejects outright,
            // and which is why `:anima:1.21.11` did not compile. A same-in-same-out replacement is
            // Stonecutter's own way to say "not this one": the engine gives the longer match
            // priority, so the accessor's name shields itself from the rule above.
            replace("SavedDataStorageAccessor", "SavedDataStorageAccessor")
        }
    }
}
