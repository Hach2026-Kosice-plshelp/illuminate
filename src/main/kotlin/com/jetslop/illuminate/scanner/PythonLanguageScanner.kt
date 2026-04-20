package com.jetslop.illuminate.scanner

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetslop.illuminate.model.*

/**
 * Scans Python source files (.py).
 * Uses PyCharm's Python PSI API via reflection so the plugin stays loadable in IntelliJ IDEA
 * (without PyCharm) — all Python-specific calls are wrapped in try/catch.
 *
 * Supports: Django (Model, View, Serializer), Flask/FastAPI routes, standard classes.
 */
class PythonLanguageScanner(project: Project) : LanguageScanner(project) {

    override val extensions = setOf("py")

    // fqName → raw PSI element (Any to avoid hard compile-time dependency)
    private val classMap = mutableMapOf<String, Any>()

    override fun collectFile(
        vFile: VirtualFile,
        nodes: MutableList<FileNode>,
        projectClasses: MutableSet<String>
    ) {
        try {
            val psiManager = PsiManager.getInstance(project)
            val psiFile = psiManager.findFile(vFile) ?: return

            // Use reflection to access PyFile without a compile-time dependency
            if (psiFile.javaClass.name != "com.jetbrains.python.psi.impl.PyFileImpl" &&
                !psiFile.javaClass.superclass?.name?.contains("PyFile", true)!! &&
                !psiFile.javaClass.interfaces.any { it.name.contains("PyFile") }
            ) return

            val topLevelClasses = psiFile.javaClass
                .getMethod("getTopLevelClasses")
                .invoke(psiFile) as? Array<*> ?: return

            // Derive a pseudo-package from the file path relative to source roots
            val pkg = derivePackage(vFile)

            for (pyClass in topLevelClasses) {
                pyClass ?: continue
                val simpleName = pyClass.javaClass.getMethod("getName").invoke(pyClass) as? String ?: continue
                val qName = if (pkg.isEmpty()) simpleName else "$pkg.$simpleName"

                projectClasses.add(qName)
                classMap[qName] = pyClass

                val decorators = getDecorators(pyClass)
                val bases = getSuperClassNames(pyClass)
                val type = classifyPython(simpleName, decorators, bases)
                val methods = getMethods(pyClass)
                val moduleName = qName.substringBeforeLast('.').split('.').take(3).joinToString(".")

                nodes.add(FileNode(
                    id = qName,
                    name = simpleName,
                    qualifiedName = qName,
                    path = vFile.path,
                    type = type,
                    module = moduleName,
                    lines = vFile.contentsToByteArray().toString(Charsets.UTF_8).lines().size,
                    methods = methods,
                    annotations = decorators
                ))
            }
        } catch (_: Exception) {
            // Python plugin not available or PSI error — silently skip
        }
    }

    override fun resolveDependencies(
        projectClasses: Set<String>,
        edges: MutableList<DependencyEdge>
    ) {
        try {
            for ((qName, pyClass) in classMap) {
                val bases = getSuperClassNamesFromMap(pyClass)
                for (base in bases) {
                    val resolved = projectClasses.firstOrNull { it.substringAfterLast('.') == base && it != qName }
                        ?: continue
                    edges.add(DependencyEdge(qName, resolved, EdgeType.EXTENDS))
                }
            }
        } catch (_: Exception) { }
        classMap.clear()
    }

    // ---- Reflection helpers ------------------------------------------------

    private fun getDecorators(pyClass: Any): List<String> = try {
        val decoratorList = pyClass.javaClass
            .getMethod("getDecoratorList")
            .invoke(pyClass)
        if (decoratorList == null) emptyList()
        else {
            val decorators = decoratorList.javaClass
                .getMethod("getDecorators")
                .invoke(decoratorList) as? Array<*>
            decorators?.mapNotNull { d ->
                d?.javaClass?.getMethod("getName")?.invoke(d) as? String
            } ?: emptyList()
        }
    } catch (_: Exception) { emptyList() }

    private fun getSuperClassNames(pyClass: Any): List<String> = try {
        val superClassExpressions = pyClass.javaClass
            .getMethod("getSuperClassExpressions")
            .invoke(pyClass) as? Array<*>
        superClassExpressions?.mapNotNull { expr ->
            expr?.javaClass?.getMethod("getName")?.invoke(expr) as? String
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun getSuperClassNamesFromMap(pyClass: Any) = getSuperClassNames(pyClass)

    private fun getMethods(pyClass: Any): List<String> = try {
        val methods = pyClass.javaClass
            .getMethod("getMethods")
            .invoke(pyClass) as? Array<*>
        methods?.mapNotNull { m ->
            m?.javaClass?.getMethod("getName")?.invoke(m) as? String
        }?.filter { it != "__init__" && it != "__str__" && it != "__repr__" } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun derivePackage(vFile: VirtualFile): String {
        // Walk up directories looking for __init__.py to build package path
        val parts = mutableListOf<String>()
        var dir = vFile.parent
        while (dir != null) {
            val hasInit = dir.children.any { it.name == "__init__.py" }
            if (!hasInit) break
            parts.add(0, dir.name)
            dir = dir.parent
        }
        return parts.joinToString(".")
    }

    private fun classifyPython(name: String, decorators: List<String>, bases: List<String>): NodeType {
        val d = decorators.map { it.lowercase() }.toSet()
        val b = bases.toSet()
        return when {
            name == "main" || name.lowercase().contains("application") -> NodeType.ENTRY_POINT
            d.any { it.contains("route") || it.contains("get") || it.contains("post") ||
                    it.contains("api_view") } -> NodeType.CONTROLLER
            b.any { it in setOf("View", "APIView", "ViewSet", "ModelViewSet", "GenericAPIView") } -> NodeType.CONTROLLER
            b.any { it in setOf("Model",) } || name.endsWith("Model") -> NodeType.ENTITY
            b.any { it.contains("Serializer") } || name.endsWith("Serializer") -> NodeType.MODEL
            b.any { it.contains("Form") } || name.endsWith("Form") -> NodeType.MODEL
            b.any { it.contains("Repository") } || name.endsWith("Repository") -> NodeType.REPOSITORY
            name.endsWith("Service") -> NodeType.SERVICE
            name.endsWith("Config") || name.endsWith("Settings") -> NodeType.CONFIG
            name.endsWith("Test") || name.startsWith("Test") -> NodeType.TEST
            name.endsWith("Utils") || name.endsWith("Helper") -> NodeType.UTIL
            else -> categoriseByName(name)
        }
    }
}
