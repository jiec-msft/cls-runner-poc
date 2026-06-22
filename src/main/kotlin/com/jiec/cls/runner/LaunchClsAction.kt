package com.jiec.cls.runner

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path
import kotlin.io.path.exists

private const val PLUGIN_ID = "com.jiec.cls.runner"
private const val NOTIFICATION_GROUP = "CLS Runner"

/**
 * The whole POC: find the bundled copilot-language-server for the current OS/arch under the
 * plugin's own install path and start it with `--stdio`. We only prove the absolute-path launch
 * mechanism (the same one the real plugin uses); we do not speak LSP to the process.
 */
class LaunchClsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        // No file/process I/O on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val (type, message) = runCatching { launch() }.fold(
                onSuccess = { NotificationType.INFORMATION to it },
                onFailure = { NotificationType.ERROR to (it.message ?: it.toString()) },
            )
            ApplicationManager.getApplication().invokeLater {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification("CLS Runner (POC)", message, type)
                    .notify(project)
            }
        }
    }

    private fun launch(): String {
        val binary = resolveBinary()
        if (!SystemInfo.isWindows) {
            // java.util.zip drops the unix executable bit, so the unpacked binary needs +x.
            binary.toFile().setExecutable(true)
        }
        val process = ProcessBuilder(binary.toString(), "--stdio")
            .redirectErrorStream(true)
            .start()
        return buildString {
            append("Launched: ").append(binary).append('\n')
            append("Args: --stdio\n")
            append("PID: ").append(runCatching { process.pid() }.getOrDefault(-1L)).append('\n')
            append("Alive after start: ").append(process.isAlive)
        }
    }

    private fun resolveBinary(): Path {
        val pluginPath = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath
            ?: error("Plugin $PLUGIN_ID not found via PluginManagerCore")
        val platform = currentNpmPlatform()
        val exeName = if (SystemInfo.isWindows) "copilot-language-server.exe" else "copilot-language-server"
        val binary = pluginPath
            .resolve("copilot-agent").resolve("native").resolve(platform).resolve(exeName)
        check(binary.exists()) { "Bundled CLS binary not found at $binary (platform=$platform)" }
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
