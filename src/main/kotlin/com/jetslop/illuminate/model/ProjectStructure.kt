package com.jetslop.illuminate.model

import com.google.gson.annotations.SerializedName

data class ProjectStructure(
    val nodes: List<FileNode>,
    val edges: List<DependencyEdge>,
    val modules: List<ProjectModule>,
    val stats: ProjectStats,
    val startHerePath: List<StartHereItem> = emptyList(),
    val summaries: MutableMap<String, String> = mutableMapOf()
)

data class FileNode(
    val id: String,
    val name: String,
    val qualifiedName: String,
    val path: String,
    val type: NodeType,
    val module: String,
    val lines: Int,
    val methods: List<String> = emptyList(),
    val annotations: List<String> = emptyList(),
    var importance: Double = 0.0,
    var summary: String = ""
)

enum class NodeType(val label: String, val color: String) {
    @SerializedName("entryPoint")
    ENTRY_POINT("Entry Point", "#ef4444"),

    @SerializedName("controller")
    CONTROLLER("Controller", "#3b82f6"),

    @SerializedName("service")
    SERVICE("Service", "#a855f7"),

    @SerializedName("repository")
    REPOSITORY("Repository", "#14b8a6"),

    @SerializedName("model")
    MODEL("Model", "#22c55e"),

    @SerializedName("entity")
    ENTITY("Entity", "#10b981"),

    @SerializedName("config")
    CONFIG("Configuration", "#6366f1"),

    @SerializedName("util")
    UTIL("Utility", "#f59e0b"),

    @SerializedName("interface")
    INTERFACE("Interface", "#06b6d4"),

    @SerializedName("enum")
    ENUM("Enum", "#8b5cf6"),

    @SerializedName("test")
    TEST("Test", "#64748b"),

    @SerializedName("other")
    OTHER("Other", "#94a3b8")
}

data class DependencyEdge(
    val source: String,
    val target: String,
    val type: EdgeType
)

enum class EdgeType {
    @SerializedName("extends") EXTENDS,
    @SerializedName("implements") IMPLEMENTS,
    @SerializedName("uses") USES,
    @SerializedName("injects") INJECTS
}

data class ProjectModule(
    val name: String,
    val path: String,
    val fileCount: Int,
    val color: String,
    val summary: String = ""
)

data class ProjectStats(
    val totalFiles: Int,
    val totalClasses: Int,
    val totalDependencies: Int,
    val totalModules: Int,
    val totalLines: Int
)

data class StartHereItem(
    val order: Int,
    val node: FileNode,
    val reason: String,
    val readingTimeMinutes: Int,
    val category: String = "core"
)
