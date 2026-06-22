package com.jiec.cls.runner

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Resolves the bundled `copilot-language-server` binary for the current OS/arch under the plugin's
 * own install path — the same absolute-path lookup the real plugin uses.
 */
object ClsBinary {

    private const val PLUGIN_ID = "com.jiec.cls.runner"

    fun resolveForCurrentPlatform(): Path {
        val pluginPath = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath
            ?: error("Plugin $PLUGIN_ID not found via PluginManagerCore")
        val platform = currentNpmPlatform()
        val exeName = if (SystemInfo.isWindows) "copilot-language-server.exe" else "copilot-language-server"
        val binary = pluginPath
            .resolve("copilot-agent").resolve("native").resolve(platform).resolve(exeName)
        check(binary.exists()) { "Bundled CLS binary not found at $binary (platform=$platform)" }
        if (!SystemInfo.isWindows) {
            // java.util.zip drops the unix executable bit, so the unpacked binary needs +x.
            binary.toFile().setExecutable(true)
        }
        return binary
    }

    private fun currentNpmPlatform(): String {
        val os = when {
            SystemInfo.isWindows -> "win32"
            SystemInfo.isMac -> "darwin"
            else -> "linux"
        }
        val arch = if (SystemInfo.isAarch64) "arm64" else "x64"
        return "$os-$arch"
    }
}
