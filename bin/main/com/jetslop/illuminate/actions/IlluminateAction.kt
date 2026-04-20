package com.jetslop.illuminate.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.jetslop.illuminate.services.IlluminateService

class IlluminateAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = IlluminateService.getInstance(project)

        // Open the tool window
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Illuminate")
        toolWindow?.show()

        // Start scanning — onComplete shows the one-time popup, onGraphUpdate silently refreshes graph
        service.scanProject(
            onComplete = { structure ->
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    "Scanned ${structure.stats.totalClasses} classes, " +
                            "${structure.stats.totalDependencies} dependencies, " +
                            "${structure.stats.totalModules} modules.\n" +
                            "Total: ${structure.stats.totalLines} lines of code.",
                    "Illuminate — Scan Complete"
                )
            },
            onGraphUpdate = { /* summaries updated — graph refreshes via notifyListeners, no popup */ }
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
