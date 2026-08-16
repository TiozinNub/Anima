pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"

    // Cross-compat between 26.1+ (unobfuscated) and older versions (https://codeberg.org/KikuGie/loom-back-compat)
    id("dev.kikugie.loom-back-compat") version "0.4"

    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The Minecraft targets this mod builds against.
//
// ⚠ THESE MUST STAY IN STEP WITH AUTARKIA'S (and Fidelia's) OWN LIST. Until 2026-08-16 one
// `targets` list in one repo made that structural; now it is a promise across repositories, and
// the same is true of the per-node `deps.fabric_api` pins in stonecutter.properties.toml. A
// consumer built against a different Fabric API than the library it loads beside does not fail at
// build time — it fails in somebody's game. See the repo-split design in the Anima-Workspace repo.
//
// 1.20.1 and 1.21.1 are TEMPORARILY DROPPED — they were dropped because the Person entity needs
// the 1.21.9+ Avatar/render pipeline, which is AUTARKIA's constraint rather than this library's.
// Anima itself may well build on them. They stay dropped here only so the two mods carry the same
// node set; revisit once Autarkia's own list moves.
val targets = listOf(
    "1.21.11" to "1.21.11",
    "26.1.x" to "26.1.2",
    "26.2.x" to "26.2",
)

stonecutter {
    create(rootProject) {
        // A ROOT branch, unlike the Anima-Workspace tree this repo was split out of: one mod, one
        // source root at `src/`, node paths of `:<version>` rather than `:<mod>:<version>`.
        targets.forEach { (proj, ver) -> version(proj, ver) }

        // Primary dev target: 26.1.x (Sinytra Connector's primary supported line)
        vcsVersion = "26.1.x"
    }
}

rootProject.name = "Anima"
