package com.jiec.cls.runner

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Owns the single bundled `copilot-language-server` process this plugin launches.
 *
 * Holding the [Process] here (instead of starting and forgetting it in the action) means the user
 * can stop it from the UI, and — because this is an application-level [Disposable] disposed on IDE
 * shutdown — the process is always killed with the IDE instead of leaking an orphan the user has to
 * hunt down in Task Manager.
 */
@Service(Service.Level.APP)
class ClsProcessService : Disposable {

    private val log = logger<ClsProcessService>()
    private val lock = Any()
    private var process: Process? = null

    val isRunning: Boolean
        get() = synchronized(lock) { process?.isAlive == true }

    val runningPid: Long?
        get() = synchronized(lock) {
            process?.takeIf { it.isAlive }?.let { runCatching { it.pid() }.getOrNull() }
        }

    /** Starts the process if not already running; returns the live PID. */
    fun start(binary: Path): Long {
        synchronized(lock) {
            process?.let { if (it.isAlive) return it.pid() }
            val started = ProcessBuilder(binary.toString(), "--stdio")
                .redirectErrorStream(true)
                .start()
            process = started
            log.info("Started copilot-language-server --stdio (pid=${started.pid()}) from $binary")
            return started.pid()
        }
    }

    /** Stops the running process; returns the PID that was stopped, or null if none was running. */
    fun stop(): Long? {
        val target = synchronized(lock) { process.also { process = null } } ?: return null
        if (!target.isAlive) return null
        val pid = runCatching { target.pid() }.getOrNull()
        target.destroy()
        if (!target.waitFor(2, TimeUnit.SECONDS)) {
            target.destroyForcibly()
            target.waitFor(2, TimeUnit.SECONDS)
        }
        log.info("Stopped copilot-language-server (pid=$pid)")
        return pid
    }

    override fun dispose() {
        stop()
    }

    companion object {
        fun getInstance(): ClsProcessService = service()
    }
}
