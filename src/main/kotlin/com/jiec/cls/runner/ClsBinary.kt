package com.jiec.cls.runner

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.system.CpuArch
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the bundled `copilot-language-server` binary for the current OS/arch under the plugin's
 * own install path. Mirrors jb CopilotAgentCommandLine.findBinary / CopilotPlugin.getPluginBasePath.
 */
object ClsBinary {

    fun resolveForCurrentPlatform(): Path {
        val binDir = pluginBasePath().resolve("copilot-agent").resolve("native")
        val platform = currentNpmPlatform()
        val exeName = if (SystemInfoRt.isWindows) "copilot-language-server.exe" else "copilot-language-server"
        val binary = binDir.resolve(platform).resolve(exeName)
        check(Files.exists(binary)) { "Bundled CLS binary not found at $binary (platform=$platform)" }
        // The unzipped plugin.zip doesn't necessarily restore the unix executable bit.
        if (SystemInfoRt.isUnix && !Files.isExecutable(binary)) {
            FileUtil.setExecutable(binary.toFile())
        }
        return binary
    }

    /**
     * This plugin's own install path, read from its [PluginAwareClassLoader] -- the public-API
     * replacement for the now-internal PluginManagerCore.getPlugin(PluginId): a plugin is expected
     * to read its own descriptor through its class loader rather than querying the plugin manager.
     * Mirrors jb CopilotPlugin.getPluginBasePath().
     */
    private fun pluginBasePath(): Path {
        val classLoader = ClsBinary::class.java.classLoader
        check(classLoader is PluginAwareClassLoader) {
            "Unable to resolve the plugin descriptor: ${ClsBinary::class.java.name} was not loaded by a " +
                "PluginAwareClassLoader (actual: ${classLoader?.javaClass?.name})"
        }
        return classLoader.pluginDescriptor.pluginPath
    }

    private fun currentNpmPlatform(): String {
        val os = when {
            SystemInfoRt.isWindows -> "win32"
            SystemInfoRt.isMac -> "darwin"
            else -> "linux"
        }
        val arch = if (CpuArch.isArm64()) "arm64" else "x64"
        return "$os-$arch"
    }
}
