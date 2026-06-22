import com.jiec.cls.runner.gradle.NativePluginPatcher
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.jiec.cls"

val pocBaseVersion = providers.gradleProperty("pocBaseVersion").get()      // 1.13.0 (stable) | 1.13.1-nightly.<run> (nightly)
val fatSinceBuild = providers.gradleProperty("sinceBuild").get()           // 251
val fatUntilBuild = providers.gradleProperty("untilBuild").get()           // 260.*
val nativeSinceBuild = providers.gradleProperty("nativeSinceBuild").get()  // 261
// fat carries the -251 suffix; slims get -261-{os}-{arch} via NativePluginPatcher.
val fatVersion = "$pocBaseVersion-$fatSinceBuild"                          // e.g. 1.13.0-251

version = fatVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1.7")
    }
}

intellijPlatform {
    // Kotlin-only plugin with no @NotNull / GUI-form instrumentation needs; disabling avoids
    // IPGP's instrumentCode task (and its JDK-compiler lookup) entirely.
    instrumentCode = false

    pluginConfiguration {
        id = "com.jiec.cls.runner"
        name = "CLS Runner (POC)"
        version = fatVersion
        ideaVersion {
            sinceBuild = fatSinceBuild
            untilBuild = fatUntilBuild
        }
    }
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// --- Bundle the 6 real CLS binaries into the plugin distribution. ---
// Anchored to IPGP's own pluginDirectory so we follow whatever sandbox layout IPGP uses.
// Sync targets the copilot-agent SUBDIR only, so it never deletes lib/.
val prepareSandboxProvider = tasks.named<PrepareSandboxTask>("prepareSandbox")

val copyAgentToSandbox = tasks.register<Sync>("copyAgentToSandbox") {
    into(prepareSandboxProvider.flatMap { it.pluginDirectory.map { dir -> dir.dir("copilot-agent") } })
    from(layout.projectDirectory.dir("copilot-agent")) {
        include("native/**")
    }
    dependsOn(prepareSandboxProvider)
    doNotTrackState("large native binaries; copy every time to avoid a missing-agent sandbox")
}

tasks.named<Zip>("buildPlugin") {
    dependsOn(copyAgentToSandbox)
}

tasks.named("runIde") {
    dependsOn(copyAgentToSandbox)
}

// --- Repack the fat ZIP into 6 OS/arch-specific slim ZIPs. ---
// Mirrors microsoft/copilot-intellij PR #12887 (buildNativePlugins / NativePluginPatcher),
// with this POC's version-suffix scheme (261-{os}-{arch}).
tasks.register("buildNativePlugins") {
    group = "intellij platform"
    description = "Builds 6 OS/arch-specific slim distribution ZIPs from buildPlugin output (2026.1+)."

    val buildPluginTask = tasks.named<Zip>("buildPlugin")
    dependsOn(buildPluginTask)

    val sourceZip = buildPluginTask.flatMap { it.archiveFile }
    val outputDir = layout.buildDirectory.dir("native-distributions")
    val coreVersion = pocBaseVersion
    val fatVer = fatVersion
    val sinceBuild = nativeSinceBuild

    inputs.file(sourceZip)
    inputs.property("coreVersion", coreVersion)
    inputs.property("sinceBuild", sinceBuild)
    outputs.dir(outputDir)

    doLast {
        val source = sourceZip.get().asFile
        check(source.name.contains(fatVer)) {
            "buildPlugin archive '${source.name}' does not contain version '$fatVer'; slim ZIP names would collide"
        }
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        NativePluginPatcher.NATIVE_TARGETS.forEach { target ->
            val newVersion = "$coreVersion-${target.versionSuffix}"
            val targetZip = outDir.resolve(source.name.replace(fatVer, newVersion))
            NativePluginPatcher.patchDistributionZip(source, targetZip, target, fatVer, newVersion, sinceBuild)
            logger.lifecycle("Built ${targetZip.name} (${targetZip.length() / (1024 * 1024)} MB)")
        }
    }
}
