package com.jiec.cls.runner.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Repacks the universal fat `buildPlugin` ZIP into 6 OS/arch-specific "slim" distributions.
 *
 * Adapted from microsoft/copilot-intellij PR #12887 (`NativePluginPatcher`) for this POC. The
 * mechanism is identical; only the version-suffix scheme differs (the POC uses
 * `261-{windows|macos|linux}-{x64|arm64}`).
 *
 * Since 2026.1 (build 261) the IntelliJ Platform exposes OS and CPU architecture as virtual
 * modules (`com.intellij.modules.os.*` / `com.intellij.modules.arch.*`) and JetBrains Marketplace
 * routes plugin downloads by them. The fat ZIP bundles all six ~90-110 MB language-server binaries
 * while every user runs exactly one; for each target this patcher therefore:
 *  1. drops the five `copilot-agent/native/<platform>/` directories that do not match the target;
 *  2. rewrites `plugin.xml` inside the plugin JAR — replaces the version, injects the os/arch
 *     `<depends>`, and raises `since-build` to 261 (dropping any until-build);
 *  3. renames the plugin JAR to match the new version.
 *
 * NOTE: two naming schemes are in play and must not be mixed up. Bundled binaries live under npm
 * platform ids (`win32-x64`, `darwin-arm64`, ...); the IntelliJ platform modules use
 * `windows`/`mac`/`linux` + `x86_64`/`arm64`. This class is the single mapping point.
 */
object NativePluginPatcher {

    /** One slim-distribution target. */
    data class NativeTarget(
        /** `com.intellij.modules.os.<osModule>`: windows | mac | linux. */
        val osModule: String,
        /** `com.intellij.modules.arch.<archModule>`: x86_64 | arm64. */
        val archModule: String,
        /** Selects the bundled `copilot-agent/native/<npmPlatform>/` directory to keep. */
        val npmPlatform: String,
        /** Cosmetic version/file-name suffix, e.g. `261-windows-x64`. */
        val versionSuffix: String,
    )

    val NATIVE_TARGETS = listOf(
        NativeTarget("windows", "x86_64", "win32-x64", "261-windows-x64"),
        NativeTarget("windows", "arm64", "win32-arm64", "261-windows-arm64"),
        NativeTarget("mac", "x86_64", "darwin-x64", "261-macos-x64"),
        NativeTarget("mac", "arm64", "darwin-arm64", "261-macos-arm64"),
        NativeTarget("linux", "x86_64", "linux-x64", "261-linux-x64"),
        NativeTarget("linux", "arm64", "linux-arm64", "261-linux-arm64"),
    )

    private const val NATIVE_DIR_MARKER = "/copilot-agent/native/"

    /**
     * Produces the slim ZIP for [target] from the universal [source] ZIP.
     *
     * @param baseVersion the full version baked into [source] (e.g. `1.13.0-251`); the string to
     *   find-and-replace in the plugin JAR name and plugin.xml.
     * @param newVersion the per-target version, e.g. `1.13.0-261-windows-x64`.
     * @param sinceBuild the since-build for the slim variant (>= 261).
     */
    fun patchDistributionZip(
        source: File,
        targetZip: File,
        target: NativeTarget,
        baseVersion: String,
        newVersion: String,
        sinceBuild: String,
    ) {
        val stats = ZipFile(source).use { zipIn ->
            ZipOutputStream(targetZip.outputStream().buffered()).use { zipOut ->
                repackEntries(zipIn, zipOut, target, baseVersion, newVersion, sinceBuild)
            }
        }
        check(stats.patchedJars == 1) {
            "Expected exactly one JAR with META-INF/plugin.xml in ${source.name}, found ${stats.patchedJars}"
        }
        check(stats.keptTargetBinary) {
            "No ${NATIVE_DIR_MARKER}${target.npmPlatform}/ entry found in ${source.name}; " +
                "the slim variant for ${target.versionSuffix} would ship without a language server"
        }
    }

    private data class RepackStats(val patchedJars: Int, val keptTargetBinary: Boolean)

    private fun repackEntries(
        zipIn: ZipFile,
        zipOut: ZipOutputStream,
        target: NativeTarget,
        baseVersion: String,
        newVersion: String,
        sinceBuild: String,
    ): RepackStats {
        var patchedJars = 0
        var keptTargetBinary = false
        for (entry in zipIn.entries()) {
            if (entry.isDirectory) continue
            if (isForeignNativeEntry(entry.name, target.npmPlatform)) continue
            if (nativePlatformSegment(entry.name) == target.npmPlatform) {
                keptTargetBinary = true
            }
            if (entry.name.endsWith(".jar")) {
                val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                if (containsPluginXml(bytes)) {
                    zipOut.putNextEntry(ZipEntry(renameForVersion(entry.name, baseVersion, newVersion)))
                    zipOut.write(patchPluginJar(bytes, target, newVersion, sinceBuild))
                    patchedJars++
                } else {
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    zipOut.write(bytes)
                }
            } else {
                // Stream copy: the kept language-server binary is ~100 MB, don't buffer it.
                zipOut.putNextEntry(ZipEntry(entry.name))
                zipIn.getInputStream(entry).use { it.copyTo(zipOut) }
            }
            zipOut.closeEntry()
        }
        return RepackStats(patchedJars, keptTargetBinary)
    }

    /** Returns the npm platform segment for entries under `copilot-agent/native/`, or null. */
    private fun nativePlatformSegment(entryName: String): String? {
        val idx = entryName.indexOf(NATIVE_DIR_MARKER)
        if (idx < 0) return null
        return entryName.substring(idx + NATIVE_DIR_MARKER.length).substringBefore('/').ifEmpty { null }
    }

    /** True for files under `copilot-agent/native/<platform>/` of a non-matching platform. */
    private fun isForeignNativeEntry(entryName: String, keepNpmPlatform: String): Boolean {
        val segment = nativePlatformSegment(entryName) ?: return false
        return segment != keepNpmPlatform
    }

    /** Renames the plugin JAR file name to carry [newVersion]; the path is kept. */
    private fun renameForVersion(entryName: String, baseVersion: String, newVersion: String): String {
        val dir = entryName.substringBeforeLast('/', "")
        val fileName = entryName.substringAfterLast('/').replace(baseVersion, newVersion)
        return if (dir.isEmpty()) fileName else "$dir/$fileName"
    }

    private fun containsPluginXml(jarBytes: ByteArray): Boolean {
        ZipInputStream(jarBytes.inputStream()).use { jarIn ->
            var entry = jarIn.nextEntry
            while (entry != null) {
                if (entry.name == "META-INF/plugin.xml") return true
                entry = jarIn.nextEntry
            }
        }
        return false
    }

    private fun patchPluginJar(
        jarBytes: ByteArray,
        target: NativeTarget,
        newVersion: String,
        sinceBuild: String,
    ): ByteArray {
        val output = ByteArrayOutputStream(jarBytes.size)
        ZipInputStream(jarBytes.inputStream()).use { jarIn ->
            ZipOutputStream(output).use { jarOut ->
                var entry = jarIn.nextEntry
                while (entry != null) {
                    val data = jarIn.readBytes()
                    jarOut.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "META-INF/plugin.xml") {
                        val patched = patchPluginXml(String(data, Charsets.UTF_8), target, newVersion, sinceBuild)
                        jarOut.write(patched.toByteArray(Charsets.UTF_8))
                    } else {
                        jarOut.write(data)
                    }
                    jarOut.closeEntry()
                    entry = jarIn.nextEntry
                }
            }
        }
        return output.toByteArray()
    }

    private val versionTagRegex = Regex("""<version>[^<]*</version>""")
    private val ideaVersionTagRegex = Regex("""<idea-version[^>]*/>""")

    /** Rewrites the already-patched (by IPGP) plugin.xml for one target. */
    internal fun patchPluginXml(
        content: String,
        target: NativeTarget,
        newVersion: String,
        sinceBuild: String,
    ): String {
        check(versionTagRegex.containsMatchIn(content)) { "No <version> tag found in plugin.xml" }
        check(ideaVersionTagRegex.containsMatchIn(content)) { "No self-closing <idea-version> tag found in plugin.xml" }

        val withDepends = content.replaceFirst(
            versionTagRegex,
            "<version>$newVersion</version>\n" +
                "    <depends>com.intellij.modules.os.${target.osModule}</depends>\n" +
                "    <depends>com.intellij.modules.arch.${target.archModule}</depends>",
        )
        // Replace the whole tag: any until-build below 261 would make a since-261 variant
        // installable nowhere.
        return withDepends.replaceFirst(ideaVersionTagRegex, """<idea-version since-build="$sinceBuild"/>""")
    }
}
