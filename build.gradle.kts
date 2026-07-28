plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
    // Anima ships its brain test doubles (FakeContext and friends) as test fixtures: a library
    // that wants other mods to write tests against its machinery has to hand them the harness.
    `java-test-fixtures`
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
}

// DO NOT set group = ...!

// Anima — the brain/nav/perception library, built from `anima/src` into its own mod jar.
// A peer branch of Autarkia, not a subproject of it: it publishes standalone to Modrinth and
// must never name a Person. See docs/superpowers/specs/2026-07-27-anima-split-design.md.
//
// This script duplicates a fair amount of `autarkia/build.gradle.kts`. That is accepted while
// there are two branches; when Fidelia lands as a third, the shared parts move into a
// `buildSrc` convention plugin.

// Same versioning rule as Autarkia: an exact `v*` tag on HEAD is a release, anything else is
// `<mod.version>-build.<commit timestamp>` (valid semver — this jar gets nested into Autarkia,
// and Loader resolves nested-jar versions as semver). One repo, one version: a tag releases
// every mod in it.
fun git(vararg args: String): String = providers.exec {
    workingDir(rootDir)
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

val exactTag = git("describe", "--tags", "--exact-match", "--match", "v*")
val modVersion = if (exactTag.startsWith("v")) exactTag.removePrefix("v")
    else "${sc.properties.get<String>("mod.version")}-build.${git("log", "-1", "--format=%cd", "--date=format:%Y%m%d%H%M%S")}"

version = "$modVersion+${sc.current.version}"
// Resolves to "anima" via the `[anima]` table in stonecutter.properties.toml — `sc.branch.id`
// is a default property tag, so `anima:mod:id` shortens to `mod:id` here and here only.
val modId: String = sc.properties["mod.id"]
base.archivesName = modId

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

val compatibleVersions: List<String> = sc.properties.rawOrNull("mod", "mc_releases")
    ?.asList().orEmpty().map { it.toString() }

repositories {
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    // YACL (optional config screen) — upstream's own maven. Note it is not always ahead of
    // Modrinth: pin per node against this repository's maven-metadata.xml, not against what
    // Modrinth lists.
    strictMaven("https://maven.isxander.dev/releases", "Xander", "dev.isxander")
}

dependencies {
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    // The library's own needs: lifecycle (server tick / world load hooks the brain driver
    // rides), command-api for `/anima`, networking for the debug viewers, entity-events for
    // agent bodies, rendering for the gizmo layers. Kept in step with the root branch's list
    // as the wiring moves across in slices 3-5.
    fapi(
        "fabric-lifecycle-events-v1", "fabric-resource-loader-v0",
        "fabric-command-api-v2", "fabric-networking-api-v1", "fabric-entity-events-v1",
        "fabric-rendering-v1", "fabric-object-builder-api-v1"
    )

    // Optional config GUI. Both are compile-only: never shipped, never required at runtime, and
    // guarded at every call site (see AnimaModMenu). YACL is used for the SCREEN only — its
    // config API is not used, because mod/config/ConfigFile keeps the atomic
    // tmp-and-rename write, the unknown-key reporting and the regenerated doc comments that
    // YACL's serializer does not offer, and core/config/ConfigStore keeps the volatile
    // whole-object swap that makes a reload safe for the off-thread pathfinder.
    // Do not bundle YACL: upstream asks not to, and it is almost always already installed.
    val yacl: String = sc.properties["deps.yacl"]
    val modMenu: String = sc.properties["deps.modmenu"]
    // isTransitive=false: YACL's POM pulls org.quiltmc.parsers (its JSON5 serializer), which
    // lives on a maven we don't otherwise need. We compile against YACL's screen API and never
    // run it — at runtime the real YACL brings its own nested copies.
    modCompileOnly("dev.isxander:yet-another-config-lib:$yacl") { isTransitive = false }
    modCompileOnly("maven.modrinth:modmenu:$modMenu")

    // core/-layer unit tests: plain JUnit, headless — no Minecraft on the test classpath.
    // The fixtures are pure core (no Minecraft), but they use the nullness annotations that
    // reach the main source set transitively through Loom. Named explicitly here.
    testFixturesCompileOnly("org.jspecify:jspecify:1.0.0")
    testFixturesImplementation(platform("org.junit:junit-bom:5.11.4"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

loom {
    // The BRANCH's own source dir (`anima/src`) — `sc.branch.project` is the safe way to say
    // `project(":anima")` from inside a node.
    fabricModJsonPath = sc.branch.project.file("src/main/resources/fabric.mod.json")

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1")
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        inputs.property("version", modVersion)
        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            put("version", modVersion)
            register("minecraft", "mod.mc_compat")
        }

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    // The licence travels with the jar: someone who has only the file, not the repository,
    // still has the terms. TRADEMARKS.md rides along because the licences say
    // nothing about the name, so the jar would otherwise imply the name came with the code.
    named<Jar>("jar") {
        from(rootProject.file("TRADEMARKS.md"))
        from(sc.branch.project.file("LICENSE"))
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", modVersion)
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/$modVersion"))
    }
}

publishMods {
    file = loomx.modJar.flatMap { it.archiveFile }
    displayName = "Anima $modVersion for MC ${sc.current.version}"
    version = project.version.toString()
    changelog = providers.environmentVariable("CHANGELOG").orElse("See the commit history.")
    type = ALPHA
    modLoaders.add("fabric")
    dryRun = providers.environmentVariable("MODRINTH_TOKEN").orNull == null

    modrinth {
        // Resolves to l8eKuisB via the `[anima]` table
        val modrinthId: String = sc.properties["publish.modrinth_id"]
        projectId = modrinthId
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        minecraftVersions.addAll(compatibleVersions)
    }
}
