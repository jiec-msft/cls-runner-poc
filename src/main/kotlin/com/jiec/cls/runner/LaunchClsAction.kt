package com.jiec.cls.runner

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

private const val NOTIFICATION_GROUP = "CLS Runner"
private const val NOTIFICATION_TITLE = "CLS Runner"

/** Builds CLS Runner balloons, including the inline "Stop CLS" action. */
internal object ClsRunnerNotifier {

    fun notify(project: Project?, type: NotificationType, message: String, withStopAction: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            val notification: Notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(NOTIFICATION_TITLE, message, type)
            if (withStopAction) {
                notification.addAction(NotificationAction.createSimple("Stop CLS") {
                    stopAsync(project)
                    notification.expire()
                })
            }
            notification.notify(project)
        }
    }

    /** Stops the process off the EDT, then reports the result. */
    fun stopAsync(project: Project?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val pid = ClsProcessService.getInstance().stop()
            val message = if (pid != null) "Stopped CLS (PID $pid)." else "No running CLS process."
            notify(project, NotificationType.INFORMATION, message, withStopAction = false)
        }
    }
}

/**
 * Tools -> "Launch CLS (--stdio)". Starts the bundled copilot-language-server from its absolute path
 * (off the EDT) via [ClsProcessService], so it can later be stopped from the UI and is killed on IDE
 * shutdown. If it is already running, reports the live PID instead of starting a second copy.
 */
class LaunchClsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        // No process/file I/O on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val service = ClsProcessService.getInstance()
            val (type, message) = runCatching {
                if (service.isRunning) {
                    NotificationType.INFORMATION to
                        "Already running (PID ${service.runningPid}). Use \"Stop CLS\" to stop it."
                } else {
                    val binary = ClsBinary.resolveForCurrentPlatform()
                    val pid = service.start(binary)
                    NotificationType.INFORMATION to "Launched: $binary\nArgs: --stdio\nPID: $pid"
                }
            }.getOrElse { NotificationType.ERROR to (it.message ?: it.toString()) }
            ClsRunnerNotifier.notify(project, type, message, withStopAction = service.isRunning)
        }
    }
}

/** Tools -> "Stop CLS". Stops the process started by [LaunchClsAction]. */
class StopClsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        ClsRunnerNotifier.stopAsync(e.project)
    }
}
