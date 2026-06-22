package com.jiec.cls.runner

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * Resolves the bundled `copilot-language-server` binary for the current OS/arch under the plugin's
 * own install path — the same absolute-path lookup the real plugin uses.
 */
object ClsBinary {

    fun resolveForCurrentPlatform(): Path {
        val platform = currentNpmPlatform()
        val exeName = if (SystemInfo.isWindows) "copilot-language-server.exe" else "copilot-language-server"
        val binary = pluginRootDir()
            .resolve("copilot-agent").resolve("native").resolve(platform).resolve(exeName)
        check(binary.exists()) { "Bundled CLS binary not found at $binary (platform=$platform)" }
        if (!SystemInfo.isWindows) {
            // java.util.zip drops the unix executable bit, so the unpacked binary needs +x.
            binary.toFile().setExecutable(true)
        }
        return binary
    }

    /**
     * Plugin root = two levels up from this plugin's own jar (`<root>/lib/<plugin>.jar`), resolved
     * from the class code source (pure JDK). This avoids PluginManagerCore.getPlugin /
     * PluginManager descriptor lookups, which are @ApiStatus.Internal and are flagged by the
     * Marketplace verifier on 2026.2+ (the universal build has no until-build, so it is checked
     * against future EAPs).
     */
    private fun pluginRootDir(): Path {
        val location = ClsBinary::class.java.protectionDomain?.codeSource?.location
            ?: error("Cannot resolve plugin install path (no code source location)")
        val jar = Paths.get(location.toURI())
        return jar.parent?.parent
            ?: error("Unexpected plugin layout at $jar (expected <root>/lib/<jar>)")
    }

    private fun currentNpmPlatform(): String {
        val os = when {
            SystemInfo.isWindows -> "win32"
            SystemInfo.isMac -> "darwin"
            else -> "linux"
        }
        val arch = if (CpuArch.CURRENT == CpuArch.ARM64) "arm64" else "x64"
        return "$os-$arch"
    }
}
