package com.jetslop.illuminate.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.jetslop.illuminate.services.IlluminateService

class IlluminateToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = IlluminateService.getInstance(project)
        val contentFactory = ContentFactory.getInstance()

        // Tab 1: Architecture Map
        val graphPanel = GraphPanel(project)
        val graphContent = contentFactory.createContent(graphPanel.component, "🗺️ Architecture", false)
        toolWindow.contentManager.addContent(graphContent)

        // Tab 2: Start Here
        val startHerePanel = StartHerePanel(project)
        val startHereContent = contentFactory.createContent(startHerePanel.component, "📖 Start Here", false)
        toolWindow.contentManager.addContent(startHereContent)

        // Tab 3: Explain Chat
        val chatPanel = ChatPanel(project)
        val chatContent = contentFactory.createContent(chatPanel.component, "💬 Explain", false)
        toolWindow.contentManager.addContent(chatContent)

        // Forward scan progress to the graph panel's progress bar
        service.progressListener = { phase, current, total ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                graphPanel.setProgress(phase, current, total)
            }
        }

        // Listen for scan updates
        service.addListener {
            val structure = service.structure
            if (service.isScanning) {
                // Show loading spinner only during a live rescan
                graphPanel.showLoading()
            } else if (structure != null) {
                graphPanel.updateGraph(structure)
                startHerePanel.updateStartHere(structure)
                chatPanel.onProjectScanned(structure)
            }
        }

        // If already scanned, populate
        service.structure?.let { structure ->
            graphPanel.updateGraph(structure)
            startHerePanel.updateStartHere(structure)
            chatPanel.onProjectScanned(structure)
        }
    }
}
