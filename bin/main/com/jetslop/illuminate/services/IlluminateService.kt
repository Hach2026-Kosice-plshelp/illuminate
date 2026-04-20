package com.jetslop.illuminate.services

import com.google.gson.Gson
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.jetslop.illuminate.ai.AIService
import com.jetslop.illuminate.model.ProjectStructure
import com.jetslop.illuminate.scanner.ProjectScanner
import com.jetslop.illuminate.settings.IlluminateSettings
import java.io.File

@Service(Service.Level.PROJECT)
@State(
    name = "IlluminateServiceState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class IlluminateService(private val project: Project) : PersistentStateComponent<IlluminateService.PersistedState> {

    private val gson = Gson()

    /** Cache file stored inside .idea/ — ignored by git, survives IDE restart */
    private fun cacheFile(): File? {
        val base = project.basePath ?: return null
        return File("$base/.idea/illuminate-cache.json")
    }

    data class ChatMessage(
        var role: String = "",
        var content: String = ""
    )

    data class PersistedState(
        var completedItems: MutableList<Int> = mutableListOf(),
        var chatMessages: MutableList<ChatMessage> = mutableListOf()
    )

    private var myState = PersistedState()

    override fun getState(): PersistedState = myState

    override fun loadState(state: PersistedState) {
        myState = state
        // Restore cached structure synchronously so the graph renders immediately (no spinner)
        try {
            val file = cacheFile()
            if (file != null && file.exists()) {
                val json = file.readText()
                if (json.isNotBlank()) {
                    structure = gson.fromJson(json, ProjectStructure::class.java)
                }
            }
        } catch (_: Exception) {
            cacheFile()?.delete()
        }
    }

    var structure: ProjectStructure? = null
        private set

    var isScanning: Boolean = false
        private set

    val completedStartHereItems: MutableSet<Int>
        get() = myState.completedItems.toMutableSet()

    fun markStartHereCompleted(order: Int) {
        if (!myState.completedItems.contains(order)) {
            myState.completedItems.add(order)
        }
    }

    fun markStartHereUncompleted(order: Int) {
        myState.completedItems.remove(order)
    }

    val chatHistory: List<ChatMessage>
        get() = myState.chatMessages.toList()

    fun addChatMessage(role: String, content: String) {
        myState.chatMessages.add(ChatMessage(role, content))
    }

    fun clearChatHistory() {
        myState.chatMessages.clear()
    }

    private val listeners = mutableListOf<() -> Unit>()
    /** Called on a background thread with (phase, current, total). */
    var progressListener: ((phase: String, current: Int, total: Int) -> Unit)? = null

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private val aiService = AIService()

    fun scanProject(onComplete: ((ProjectStructure) -> Unit)? = null, onGraphUpdate: ((ProjectStructure) -> Unit)? = null) {
        if (isScanning) return
        isScanning = true
        notifyListeners()

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val scanner = ProjectScanner(project)
                scanner.onProgress = progressListener
                val result = scanner.scan()
                structure = result

                // Persist structure to disk so next open is instant (no spinner)
                try { cacheFile()?.writeText(gson.toJson(result)) } catch (_: Exception) {}

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    isScanning = false
                    notifyListeners()
                    onComplete?.invoke(result)      // ← called ONCE, shows popup
                }

                // Background AI enrichment — updates graph silently via notifyListeners, no popup
                if (IlluminateSettings.getInstance().apiKey.isNotBlank()) {
                    enrichWithSummaries(result)
                }
            } catch (e: Exception) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    isScanning = false
                    notifyListeners()
                }
            }
        }
    }

    /**
     * Generate AI summaries for all nodes, sorted by importance (most important first).
     * Updates each node in-place and triggers a single graph re-render via notifyListeners at the end.
     * Never calls the scan-complete popup callback.
     */
    private fun enrichWithSummaries(result: ProjectStructure) {
        val sorted = result.nodes.sortedByDescending { it.importance }
        var batchCount = 0

        for (node in sorted) {
            if (node.summary.isNotBlank()) continue
            try {
                val summary = aiService.summarizeClass(node, result)
                if (summary.isNotBlank() && !summary.startsWith("Configure") && !summary.startsWith("Error")) {
                    node.summary = summary
                    batchCount++
                }
            } catch (_: Exception) { /* skip this node */ }
        }

        // Single re-render after all summaries are done — avoids repeated zoom jumps
        if (batchCount > 0) {
            structure = result
            // Update cache so AI summaries survive restart too
            try { cacheFile()?.writeText(gson.toJson(result)) } catch (_: Exception) {}
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                notifyListeners()
            }
        }
    }

    companion object {
        fun getInstance(project: Project): IlluminateService {
            return project.getService(IlluminateService::class.java)
        }
    }
}
