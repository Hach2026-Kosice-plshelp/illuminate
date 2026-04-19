package com.jetslop.illuminate.scanner

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.jetslop.illuminate.model.*

/**
 * Abstract base for per-language scanners.
 * Each implementation handles one file extension and populates shared [nodes]/[edges] lists.
 */
abstract class LanguageScanner(protected val project: Project) {

    /** File extensions handled by this scanner (lowercase, without dot). */
    abstract val extensions: Set<String>

    /**
     * Collect all top-level class/function/interface declarations from the given file.
     * Populate [nodes] and register qualified names into [projectClasses].
     */
    abstract fun collectFile(
        vFile: VirtualFile,
        nodes: MutableList<FileNode>,
        projectClasses: MutableSet<String>
    )

    /**
     * Resolve dependencies between already-collected nodes.
     * May only be called after ALL files have been collected (projectClasses is complete).
     */
    abstract fun resolveDependencies(
        projectClasses: Set<String>,
        edges: MutableList<DependencyEdge>
    )

    // Shared helpers ------------------------------------------------------------

    protected fun categoriseByName(name: String): NodeType = when {
        name.endsWith("Controller") -> NodeType.CONTROLLER
        name.endsWith("Service") || name.endsWith("ServiceImpl") -> NodeType.SERVICE
        name.endsWith("Repository") || name.endsWith("Repo") || name.endsWith("Dao") -> NodeType.REPOSITORY
        name.endsWith("DTO") || name.endsWith("Dto") || name.endsWith("VO") || name.endsWith("Request") || name.endsWith("Response") -> NodeType.MODEL
        name.endsWith("Config") || name.endsWith("Configuration") -> NodeType.CONFIG
        name.endsWith("Util") || name.endsWith("Utils") || name.endsWith("Helper") -> NodeType.UTIL
        else -> NodeType.OTHER
    }

    protected val INJECTION_ANNOTATIONS = setOf("Autowired", "Inject", "Resource")
    protected val CONTROLLER_ANNOTATIONS = setOf("RestController", "Controller", "RequestMapping", "router", "app_route")
    protected val SERVICE_ANNOTATIONS = setOf("Service", "Component", "Injectable")
    protected val REPOSITORY_ANNOTATIONS = setOf("Repository", "Mapper")
    protected val ENTITY_ANNOTATIONS = setOf("Entity", "Table", "Document", "MappedSuperclass", "dataclass")
    protected val CONFIG_ANNOTATIONS = setOf("Configuration", "EnableAutoConfiguration", "ConfigurationProperties")
    protected val ENTRY_ANNOTATIONS = setOf("SpringBootApplication", "SpringBootTest")
}
