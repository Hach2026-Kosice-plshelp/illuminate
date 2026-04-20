package com.jetslop.illuminate.scanner

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.jetslop.illuminate.model.*
import java.util.concurrent.CountDownLatch

class ProjectScanner(private val project: Project) {

    private val nodes = mutableListOf<FileNode>()
    private val edges = mutableListOf<DependencyEdge>()
    private val projectClasses = mutableSetOf<String>()

    /** Called with (phase, current, total) during scan. total=0 means indeterminate. */
    var onProgress: ((phase: String, current: Int, total: Int) -> Unit)? = null

    /** All supported language scanners, tried in order. */
    private val languageScanners: List<LanguageScanner> = buildList {
        add(JavaLanguageScanner(project))
        // Kotlin plugin is bundled with IntelliJ IDEA — always safe to include
        try { add(KotlinLanguageScanner(project)) } catch (_: Throwable) { }
        // Python plugin is optional (PyCharm / Python plugin installed)
        try { add(PythonLanguageScanner(project)) } catch (_: Throwable) { }
    }

    /** Map from file extension to the scanner that handles it. */
    private val scannerByExtension: Map<String, LanguageScanner> by lazy {
        val map = mutableMapOf<String, LanguageScanner>()
        for (scanner in languageScanners) {
            for (ext in scanner.extensions) map[ext] = scanner
        }
        map
    }

    fun scan(): ProjectStructure {
        nodes.clear()
        edges.clear()
        projectClasses.clear()

        val dumbService = DumbService.getInstance(project)
        val app = ApplicationManager.getApplication()

        // Wait for smart mode WITHOUT holding any read lock.
        // A single monolithic read lock held for 20+ seconds blocks all write actions
        // (including IntelliJ's own indexing commits) and freezes the EDT.
        if (dumbService.isDumb) {
            val latch = CountDownLatch(1)
            dumbService.runWhenSmart { latch.countDown() }
            latch.await()
        }

        // Step 1: Collect VirtualFile references — fast read action, no PSI resolution yet
        onProgress?.invoke("Discovering files...", 0, 0)
        val toProcess = mutableListOf<Pair<VirtualFile, LanguageScanner>>()
        app.runReadAction {
            ProjectFileIndex.getInstance(project).iterateContent { vFile ->
                val ext = vFile.extension?.lowercase() ?: return@iterateContent true
                if (vFile.path.contains("/test/") || vFile.path.contains("/build/")) return@iterateContent true
                val scanner = scannerByExtension[ext] ?: return@iterateContent true
                toProcess.add(vFile to scanner)
                true
            }
        }

        // Step 2: Process each file in its own small read action.
        // Short-lived read locks allow IntelliJ to acquire write locks between files.
        // Cap at MAX_FILES to prevent unbounded memory/time on truly massive repos.
        val MAX_FILES = 4000
        val capped = if (toProcess.size > MAX_FILES) toProcess.subList(0, MAX_FILES) else toProcess
        val total = capped.size
        capped.forEachIndexed { idx, (vFile, scanner) ->
            if (idx % 50 == 0) onProgress?.invoke("Parsing files...", idx, total)
            app.runReadAction {
                scanner.collectFile(vFile, nodes, projectClasses)
            }
        }

        // Step 3: Resolve dependencies — each scanner in its own read action
        onProgress?.invoke("Resolving dependencies...", 0, 0)
        for (scanner in languageScanners) {
            app.runReadAction {
                scanner.resolveDependencies(projectClasses, edges)
            }
        }

        // Deduplicate
        val unique = edges.distinctBy { Triple(it.source, it.target, it.type) }
        edges.clear()
        edges.addAll(unique)

        calculateImportance()
        val modules = detectModules()
        val startHere = generateStartHerePath()

        return ProjectStructure(
            nodes = nodes.toList(),
            edges = edges.toList(),
            modules = modules,
            stats = ProjectStats(
                totalFiles = nodes.size,
                totalClasses = nodes.size,
                totalDependencies = edges.size,
                totalModules = modules.size,
                totalLines = nodes.sumOf { it.lines }
            ),
            startHerePath = startHere
        )
    }

    private fun calculateImportance() {
        val inDegree = mutableMapOf<String, Int>()
        val outDegree = mutableMapOf<String, Int>()

        for (edge in edges) {
            inDegree[edge.target] = (inDegree[edge.target] ?: 0) + 1
            outDegree[edge.source] = (outDegree[edge.source] ?: 0) + 1
        }

        val maxConnectivity = maxOf(1,
            (inDegree.values.maxOrNull() ?: 0) + (outDegree.values.maxOrNull() ?: 0)
        ).toDouble()
        val maxLOC = maxOf(1, nodes.maxOfOrNull { it.lines } ?: 1).toDouble()
        val maxMethods = maxOf(1, nodes.maxOfOrNull { it.methods.size } ?: 1).toDouble()

        for (node in nodes) {
            val inD = inDegree[node.id] ?: 0
            val outD = outDegree[node.id] ?: 0

            // Type base — always > 0, reflects architectural role
            val typeBase = when (node.type) {
                NodeType.ENTRY_POINT -> 1.0
                NodeType.CONTROLLER  -> 0.80
                NodeType.SERVICE     -> 0.75
                NodeType.REPOSITORY  -> 0.60
                NodeType.CONFIG      -> 0.65
                NodeType.ENTITY      -> 0.55
                NodeType.INTERFACE   -> 0.50
                NodeType.MODEL       -> 0.45
                NodeType.UTIL        -> 0.40
                NodeType.ENUM        -> 0.25
                else                 -> 0.30
            }

            // Connectivity score (weighted in-degree more: being depended upon = more important)
            val connectScore = ((inD * 1.5 + outD) / (maxConnectivity * 1.5))

            // Size/complexity score (log-normalized to avoid LOC dominating)
            val locScore = Math.log1p(node.lines.toDouble()) / Math.log1p(maxLOC)
            val methodScore = node.methods.size.toDouble() / maxMethods

            // Annotation bonus: key Spring stereotypes signal importance
            val annotBonus = when {
                node.annotations.any { it in listOf("SpringBootApplication", "SpringBootTest") } -> 0.20
                node.annotations.any { it in listOf("RestController", "Controller") } -> 0.10
                node.annotations.any { it in listOf("Service", "Component") } -> 0.07
                node.annotations.any { it in listOf("Configuration") } -> 0.08
                else -> 0.0
            }

            // Weighted combination
            node.importance = minOf(
                1.0,
                connectScore * 0.45   // connectivity is the strongest signal
                + typeBase * 0.25     // architectural role
                + locScore  * 0.15   // code complexity
                + methodScore * 0.10 // number of responsibilities
                + annotBonus * 0.05  // explicit framework role
            )
        }

        // Normalise so the most important node always hits exactly 1.0
        val maxImportance = nodes.maxOfOrNull { it.importance } ?: 1.0
        if (maxImportance > 0.0) {
            for (node in nodes) {
                node.importance = node.importance / maxImportance
            }
        }
    }

    private fun detectModules(): List<ProjectModule> {
        val moduleColors = listOf(
            "#6366f1", "#a855f7", "#3b82f6", "#14b8a6",
            "#22c55e", "#f59e0b", "#ef4444", "#ec4899",
            "#06b6d4", "#8b5cf6", "#f97316", "#84cc16"
        )

        return nodes.groupBy { it.module }
            .entries.mapIndexed { idx, (moduleName, files) ->
                ProjectModule(
                    name = moduleName,
                    path = files.firstOrNull()?.path?.substringBeforeLast('/') ?: "",
                    fileCount = files.size,
                    color = moduleColors[idx % moduleColors.size]
                )
            }
    }

    private fun generateStartHerePath(): List<StartHereItem> {
        val result = mutableListOf<StartHereItem>()
        val visited = mutableSetOf<String>()

        // 1. Entry points first
        val entryPoints = nodes.filter {
            it.type == NodeType.ENTRY_POINT || it.type == NodeType.CONFIG
        }.sortedByDescending { it.importance }

        for (ep in entryPoints) {
            if (ep.id !in visited) {
                visited.add(ep.id)
                result.add(
                    StartHereItem(
                        order = result.size + 1,
                        node = ep,
                        reason = when (ep.type) {
                            NodeType.ENTRY_POINT -> "🚀 Application entry point — start here"
                            NodeType.CONFIG -> "⚙️ Configuration — sets up the application"
                            else -> "Important starting file"
                        },
                        readingTimeMinutes = estimateReadingTime(ep.lines),
                        category = "entry"
                    )
                )
            }
        }

        // 2. BFS from entry points through dependency graph
        val adjacency = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            adjacency.getOrPut(edge.source) { mutableListOf() }.add(edge.target)
        }

        val queue = ArrayDeque(entryPoints.map { it.id })
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val neighbors = adjacency[current] ?: continue
            for (neighbor in neighbors.sortedByDescending { id -> nodes.find { it.id == id }?.importance ?: 0.0 }) {
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    val node = nodes.find { it.id == neighbor } ?: continue
                    result.add(
                        StartHereItem(
                            order = result.size + 1,
                            node = node,
                            reason = buildReason(node, current),
                            readingTimeMinutes = estimateReadingTime(node.lines),
                            category = categoryFor(node.type)
                        )
                    )
                    queue.add(neighbor)
                }
            }
        }

        // 3. Remaining unvisited (isolated nodes)
        for (node in nodes.filter { it.id !in visited }.sortedByDescending { it.importance }) {
            visited.add(node.id)
            result.add(
                StartHereItem(
                    order = result.size + 1,
                    node = node,
                    reason = "📄 Supporting file in module ${node.module.substringAfterLast('.')}",
                    readingTimeMinutes = estimateReadingTime(node.lines),
                    category = categoryFor(node.type)
                )
            )
        }

        return result
    }

    private fun buildReason(node: FileNode, referencedFrom: String): String {
        val fromName = referencedFrom.substringAfterLast('.')
        return when (node.type) {
            NodeType.CONTROLLER -> "🌐 API controller — handles HTTP requests (used by $fromName)"
            NodeType.SERVICE -> "⚡ Business logic — core service (used by $fromName)"
            NodeType.REPOSITORY -> "💾 Data access layer (used by $fromName)"
            NodeType.ENTITY -> "📦 Data model / Entity (used by $fromName)"
            NodeType.MODEL -> "📋 Data transfer object (used by $fromName)"
            NodeType.INTERFACE -> "📐 Contract / Interface (implemented by $fromName)"
            NodeType.UTIL -> "🔧 Utility class (used by $fromName)"
            NodeType.ENUM -> "🏷️ Enum constants (used by $fromName)"
            else -> "📄 Related class (referenced from $fromName)"
        }
    }

    private fun categoryFor(type: NodeType): String = when (type) {
        NodeType.ENTRY_POINT, NodeType.CONFIG -> "entry"
        NodeType.CONTROLLER -> "api"
        NodeType.SERVICE -> "logic"
        NodeType.REPOSITORY, NodeType.ENTITY, NodeType.MODEL -> "data"
        else -> "support"
    }

    private fun estimateReadingTime(lines: Int): Int = maxOf(1, lines / 30)
}
