import net.ltgt.gradle.errorprone.errorprone

plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
    // Anima ships its brain test doubles (FakeContext and friends) as test fixtures: a library
    // that wants other mods to write tests against its machinery has to hand them the harness.
    `java-test-fixtures`
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
    id("net.ltgt.errorprone") version "5.1.0"
    // Anima is a library other mods compile against, so it publishes to a Maven repository as
    // well as to Modrinth. Autarkia consumes it the way a stranger would.
    `maven-publish`
}

// DO NOT set group = ...! Loom and Stonecutter both key off the project coordinates, and setting
// a group here has broken node resolution before. The Maven coordinates are set on the
// PUBLICATION instead (see `publishing` at the bottom), which is the supported seam.

// Anima — the brain/nav/perception library, built from `anima/src` into its own mod jar.
// A peer branch of Autarkia, not a subproject of it: it publishes standalone to Modrinth and
// must never name a Person. See docs/superpowers/specs/2026-07-27-anima-split-design.md.
//
// This script duplicates a fair amount of `autarkia/build.gradle.kts`. That is accepted while
// there are two branches; when Fidelia lands as a third, the shared parts move into a
// `buildSrc` convention plugin.

// Same versioning rule as Autarkia: an exact `v*` tag on HEAD is a release, anything else is
// `<mod.version>-build.<commit timestamp>` (valid semver, which Loader requires of anything it
// resolves).
//
// The tag is PREFIXED with the MOD ID — `anima-v0.2.0`, never a bare `v0.2.0`. One repo no longer
// means one version: each mod carries its own number in its own `[<mod>]` table and is released
// by its own tag, so cutting Anima does not drag Autarkia's number along behind it. A bare `v*`
// tag now releases nothing at all, which is the intended failure — it is ambiguous about which
// mod it means. See docs/superpowers/specs/2026-08-16-repo-split-design.md, slice 1.
fun git(vararg args: String): String = providers.exec {
    workingDir(rootDir)
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

// Top-level `mod.id` in stonecutter.properties.toml. This repo has a ROOT branch and one mod,
// so there are no `[<mod>]` tables and no `sc.branch.id` tag shortening a path to reach them.
// Read before the version, which is derived from it.
val modId: String = sc.properties["mod.id"]

val tagPrefix = "$modId-v"
val exactTag = git("describe", "--tags", "--exact-match", "--match", "$tagPrefix*")
val isRelease = exactTag.startsWith(tagPrefix)

// A release is the tag's number; anything else is `<mod.version>-SNAPSHOT`.
//
// -SNAPSHOT rather than the `-build.<commit timestamp>` this used to be, because these are
// PUBLISHED now. A timestamped version is unique and immutable, so every workstation build would
// mint a permanent version in the registry and the package list would become a landfill. A
// snapshot is one reusable slot: Maven timestamps the individual uploads underneath it, and
// `0.1.0-SNAPSHOT` always resolves to the newest.
//
// The cost is that the version string no longer says which build it is — which mattered, because
// reading the version out of the in-game mod list is how you tell whether the thing you just
// compiled is the thing that is running. So the commit stamp moves to the jar manifest rather
// than disappearing.
val modVersion = if (isRelease) exactTag.removePrefix(tagPrefix)
    else "${sc.properties.get<String>("mod.version")}-SNAPSHOT"

/** The commit this jar was built from — the identity that `-SNAPSHOT` does not carry. */
val buildStamp = git("log", "-1", "--format=%cd", "--date=format:%Y%m%d%H%M%S")

// The Maven coordinates. Hoisted up here rather than written inline in `publishing` because the
// test-fixtures capability below has to be spelled with the same words — see the comment
// there for what happens when it is not.
val publishGroup: String = sc.properties["mod.group"]
val publishArtifact = "$modId-${sc.current.version}"

version = "$modVersion+${sc.current.version}"
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

    // The TOML reader/writer behind `config/<mod>.toml`. A plain Java library that never sees
    // Minecraft, so it is `implementation` rather than `modImplementation`; Loom generates the
    // nested mod metadata for it at `include` time. `include` is not transitive — naming only
    // `toml` would nest a jar whose every class fails to link — so both modules are listed.
    //
    // NESTED, and it is the only thing Anima nests. The no-jar-in-jar decision (CLAUDE.md) is
    // about our mods staying separate downloads: nesting Anima into Autarkia would give a player
    // running two consumers two copies of the library and let Loader pick. A third-party parser
    // is the case that rule was never about — the alternative is telling every Anima user to
    // install a Java library by hand. LGPL-3.0, conveyed unmodified and replaceable (a newer
    // night-config in `mods/` wins. That is the relinking freedom the licence asks for);
    // the texts and the notice ride in the jar. See THIRD-PARTY.md.
    val nightConfig: String = sc.properties["deps.night_config"]
    for (module in listOf("core", "toml")) {
        implementation("com.electronwill.night-config:$module:$nightConfig")
        include("com.electronwill.night-config:$module:$nightConfig")
    }

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

    errorprone("com.google.errorprone:error_prone_core:2.50.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    // This node's own named classes, ahead of everything else. Loom resolves a project on the test
    // classpath to its published jar rather than to `build/classes/java/main` — and on a
    // Mojang-mapped node that jar is `remapJar`'s output, bytecode written against intermediary
    // names. A test then only has to DECLARE a field of a mod/-layer type for JUnit's field scan to
    // die on `NoClassDefFoundError: net/minecraft/class_18` while loading a class whose source says
    // `SavedData`. Prepending makes the named classes win the lookup. The 26.1+ nodes never saw it:
    // they are unobfuscated, so their jar holds the same names either way.
    classpath = sourceSets["main"].output + classpath

    // ArchitectureTest reads Java source as TEXT, and the text it must read is the BRANCH's
    // (`anima/src`), never this node's generated copy: the layering rules are about the one
    // shared source of truth, and the Stonecutter rule can only be asked of source that still
    // has its `//?` directives. Handed in rather than derived from the working directory, so a
    // moved node layout fails with a message instead of scanning an empty tree and passing.
    val branchSources = rootProject.file("src/main/java")
    systemProperty("anima.arch.sourceRoot", branchSources.absolutePath)
    // Declared as an input because a violation can be introduced without changing any bytecode —
    // adding a `//?` to a core file, or an import nothing uses. Without this the test task is
    // UP-TO-DATE after the edits it exists to catch.
    inputs.dir(branchSources).withPropertyName("branchSources").withPathSensitivity(PathSensitivity.RELATIVE)

    // JarContentsTest inspects the jar this build produced. `remapJar` where it exists (the
    // Mojang-mapped nodes) and `jar` where it does not (26.1+ is unobfuscated, so Loom registers
    // no remap task at all) — whichever one is the artifact that actually ships.
    //
    // Handed in rather than found: build/libs/ keeps every timestamped jar this repo has ever
    // built, hundreds of them, so "newest match for a glob" is a guess and would happily verify
    // last month's release.
    //
    // Typed `AbstractArchiveTask`, not `Jar`. Loom's RemapJarTask descends from
    // `org.gradle.jvm.tasks.Jar`, which is the SUPERclass of the `Jar` a build script means when it
    // writes the bare name (`org.gradle.api.tasks.bundling.Jar`) — so asking for `named<Jar>` on a
    // node that has a remapJar failed configuration outright with "not a subclass of the given
    // type", and every Mojang-mapped node died before compiling. The 26.1+ nodes never noticed,
    // having no remapJar to look up.
    val shippedJar = (if (tasks.names.contains("remapJar")) tasks.named<AbstractArchiveTask>("remapJar")
                      else tasks.named<AbstractArchiveTask>("jar")).flatMap { it.archiveFile }
    dependsOn(shippedJar)
    inputs.file(shippedJar).withPropertyName("shippedJar").withPathSensitivity(PathSensitivity.NAME_ONLY)
    // Resolved here rather than through a jvmArgumentProviders lambda: a lambda written in a build
    // script captures the script object, which the configuration cache cannot serialize. The path
    // itself is known at configuration time — it is derived from archivesName and version, not
    // from anything the Jar task does.
    systemProperty("anima.jar", shippedJar.get().asFile.absolutePath)
    systemProperty("anima.version", modVersion)
    // JarContentsTest pins the nested jars by name. Handed in rather than hardcoded in the test,
    // so bumping the library is still a one-line edit in stonecutter.properties.toml.
    val nightConfigVersion: String = sc.properties["deps.night_config"]
    systemProperty("anima.night_config.version", nightConfigVersion)
}

// Every warning javac can raise is an error, less four categories. `-Xlint:all` rather than a
// list, so a JDK upgrade adopts new checks on its own; `-Plint=off` is the way out on the day
// one of them arrives in the middle of something else.
//
//   classfile     gson and guava ship references to Error Prone annotations they do not bundle.
//                 Nothing here can fix a warning about the inside of somebody else's jar.
//   deprecation   Minecraft deprecates by the hundred, and some of it is deliberate here — the
//                 resource-reload listener AppearanceClient implements is kept,
//                 because its replacement renamed a method between targets.
//   this-escape   a Minecraft entity is its fields: Person builds ten organs with `this` before
//                 the constructor ends, and vanilla subclasses it. Unfixable without leaving the
//                 pattern every entity in the game uses.
//   dangling-doc-comments
//                 21 doc comments stranded above the wrong member by past refactors. Real, and
//                 worth fixing — each one is a stale design note sitting where a reader will
//                 take it for the current one — but a cleanup with 21 judgement calls in it, not
//                 a lint switch. Delete this line when they are gone.
//
// The last two are spelled out only where the toolchain has them. `-Xlint` rejects the whole flag
// with `invalid flag` (not a warning, a compilation-initialization error) the moment it reads a
// category it does not know, so naming one costs every node compiled by an older JDK. `this-escape`
// arrived in 21 and `dangling-doc-comments` in 22; the 1.21.11 node builds on 21. That is what
// made `:anima:1.21.11:compileJava` fail before it read a single source file.
//
// Error Prone rides along on the same switch. Its ERROR tier is the part that earns its keep —
// around a hundred high-confidence bug patterns, on by default, and both mods pass every one of
// them today, so what it actually buys is a guard on every future commit. Its WARNING tier is
// switched off wholesale rather than triaged here: 250 findings across the two mods, most of them
// documentation drift of the same species as the dangling comments above, and with `-Werror` on
// every single one would be a build failure. Turning a check back on is one line
// (`check("Name", CheckSeverity.ERROR)`), and that is the shape the cleanup should take — one
// check at a time, cleaned then enforced, rather than 250 findings in one sitting.
tasks.withType<JavaCompile>().configureEach {
    val lint = providers.gradleProperty("lint").orNull != "off"
    if (lint) {
        val muted = buildList {
            add("classfile")
            add("deprecation")
            if (requiredJava >= JavaVersion.VERSION_21) add("this-escape")
            if (requiredJava >= JavaVersion.VERSION_22) add("dangling-doc-comments")
        }
        options.compilerArgs.addAll(
            listOf(muted.joinToString(",-", prefix = "-Xlint:all,-"), "-Werror")
        )
    }
    options.errorprone {
        isEnabled = lint
        // A mixin is bytecode surgery written as Java. An injector's parameters must match the
        // target method's signature whether the handler reads them or not, and a method a mixin
        // injects is called by the mixin machinery and never from Java. Error Prone reads both as
        // dead code — correctly by its rules, wrongly by ours.
        excludedPaths = ".*/mixin/.*"
        disableAllWarnings = true
    }
}

// A running dev game holds these jars OPEN and reads mod classes out of them lazily — Autarkia
// carries Anima on its classpath as a jar, and a dev client keeps its own. Gradle rewrites an
// archive in place, which leaves every class the live JVM has not loaded yet unreadable
// ("ZipException: invalid LOC header"); the game then dies minutes later on the first unseen
// class, with nothing in the crash pointing back at a build. It does not have to be your build:
// a parallel session's compile crashed a dev client and a test server on 2026-08-02.
// Unlinking first makes the rewrite land on a new inode, and an open file descriptor follows the
// inode — so every already-running game keeps reading the bytes it booted with.
tasks.withType<Jar>().configureEach {
    doFirst { archiveFile.get().asFile.delete() }
}

loom {
    // The root project's own source dir. Was `sc.branch.project.file(...)` while this mod was a
    // branch of a shared tree; with a root branch the branch project is the root project.
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")

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
    // still has the terms.
    //
    // licenses/ rides along for the nested night-config, which is LGPL-3.0 and ships no licence
    // text of its own — so if we did not carry one, nobody downloading this jar would ever see
    // the terms of the library inside it. That is an obligation, not a courtesy. Both GNU texts,
    // because the LGPL is not a whole licence: it is additional permissions written on top of the
    // GPL and incorporates it by reference.
    //
    // TRADEMARKS.md and THIRD-PARTY.md were dropped on 2026-08-16 while the legal docs are
    // reworked (the files themselves are gone). A `from()` naming a missing file is a silent
    // no-op in Gradle, so these lines went too rather than staying as decoration that looks like
    // it does something. What lapsed is the PROSE — the name reservation, and the sentence saying
    // which library the GNU texts below belong to — not the licence terms.
    named<Jar>("jar") {
        // Which commit this is. The version string stopped saying so when dev builds became
        // `-SNAPSHOT`, and "is the running game the code I just compiled" is a question this
        // project asks constantly. `unzip -p <jar> META-INF/MANIFEST.MF` answers it.
        manifest.attributes(
            "Implementation-Title" to (sc.properties["mod.name"] as String),
            "Implementation-Version" to modVersion,
            "Implementation-Build" to buildStamp,
            "Minecraft-Version" to sc.current.version,
        )

        from(rootProject.file("LICENSE"))
        from(rootProject.file("licenses")) { into("licenses") }
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
        // Resolves to l8eKuisB from the top-level `publish.modrinth_id`
        val modrinthId: String = sc.properties["publish.modrinth_id"]
        projectId = modrinthId
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        minecraftVersions.addAll(compatibleVersions)
    }
}

// ── Maven ──────────────────────────────────────────────────────────────────────────────────
//
// Modrinth is where a PLAYER gets this mod; Maven is where a DEVELOPER gets it. Autarkia resolves
// Anima from here, through the coordinates a stranger would use — if this path is awkward
// for the mod in the next directory, it is awkward for everyone, and we find that out ourselves
// rather than from a bug report.
//
// ⚠ THE MINECRAFT VERSION IS IN THE ARTIFACT ID, not the version string. Every node publishes from
// this one repo, so the coordinate has to distinguish them or the 26.1.2 build silently overwrites
// the 1.21.11 one. It cannot go in the version because Maven decides a version is a snapshot by
// `endsWith("-SNAPSHOT")` — so the ecosystem-familiar `0.1.0-SNAPSHOT+26.1.2` would be treated as
// an ordinary immutable release, which is the exact thing -SNAPSHOT exists to avoid. Putting it in
// the artifact id keeps the version a clean semver string and the snapshot a real snapshot:
//
//     dev.luizloyola:anima-26.1.2:0.1.0-SNAPSHOT     (dev)
//     dev.luizloyola:anima-26.1.2:0.2.0              (released by an `anima-v0.2.0` tag)
//
// The jar on disk still encodes it the other way round (`anima-0.1.0-SNAPSHOT+26.1.2.jar`) because
// that is the Fabric convention for a FILE a player downloads. Same two facts, two audiences.
// ⚠ The test-fixtures capability has to be RESTATED to match the publication, or a consumer
// cannot resolve the fixtures at all.
//
// Gradle derives it from the PROJECT — `${group}:${name}-test-fixtures:${version}` — which here is
// `Anima:26.1.x-test-fixtures:0.1.0-SNAPSHOT+26.1.2`: the root project's name, the version node's
// name, and the jar's version. maven-publish rewrites the coordinates of the main variants to the
// ones set below, but a capability is an opaque string it never touches, so the published metadata
// advertised the project's identity while a consumer writing
// `testFixtures("dev.luizloyola:anima-26.1.2:0.1.0-SNAPSHOT")` asked for a capability derived from
// the COORDINATES. Nothing matched, and Autarkia's build failed with "Unable to find a variant with
// the requested capability: feature 'test-fixtures'" while the main jar resolved perfectly — which
// is a confusing shape of failure, because the artifact is plainly right there.
//
// The variant ends up advertising both names — `java-test-fixtures` registers its own explicitly,
// so this adds to it rather than replacing it (the usual "declaring a capability drops the implicit
// one" rule does not apply, because the plugin's was never implicit). That is harmless: a variant
// may provide several capabilities, and a consumer matches on whichever one it asked for. Verified
// in the published .module — both appear, and Autarkia resolves through the coordinate-shaped one.
listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { conf ->
    configurations.named(conf) {
        outgoing.capability("$publishGroup:$publishArtifact-test-fixtures:$modVersion")
    }
}

publishing {
    publications {
        create<MavenPublication>("mod") {
            groupId = publishGroup
            artifactId = publishArtifact
            version = modVersion

            // The whole component rather than hand-listed artifacts: it carries the test-fixtures
            // variant (Autarkia's chop and architecture tests drive FakeContext, and Fidelia will
            // want the same harness) and it writes real POM dependencies. Hand-listing artifacts
            // would publish a POM with no dependencies at all, and night-config — which Anima's
            // config machinery reads on its CONSUMER's behalf — would silently not be there.
            from(components["java"])

            pom {
                name = sc.properties["mod.name"] as String
                description = "The machinery of souls, for any body — brains, navigation, " +
                        "perception and journalling for autonomous agents in Minecraft."
                url = "https://modrinth.com/mod/anima"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
            }
        }
    }

    repositories {
        // A plain directory, for the Anima-Workspace helper: it publishes this library here and
        // points Autarkia at it, so a change to the library reaches its consumer without a round
        // trip through a server. Not `mavenLocal()` — ~/.m2 is machine-global and
        // parallel sessions share one checkout on this box, so two sessions publishing different
        // Animas would poison each other's builds with no sign of where it came from.
        maven {
            name = "LocalMaven"
            url = uri(providers.gradleProperty("localMaven")
                .getOrElse(rootProject.layout.buildDirectory.dir("local-maven").get().asFile.path))
        }

        maven {
            name = "Gitea"
            url = uri("https://gitea.luizloyola.dev/api/packages/TiozinNub/maven")
            credentials {
                username = providers.environmentVariable("GITEA_USER").getOrElse("TiozinNub")
                password = providers.environmentVariable("GITEA_TOKEN").orNull
            }
        }
    }
}
