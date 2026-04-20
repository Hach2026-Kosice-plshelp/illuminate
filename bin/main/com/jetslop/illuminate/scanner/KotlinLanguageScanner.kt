package com.jetslop.illuminate.scanner

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetslop.illuminate.model.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForReceiver

/**
 * Scans Kotlin source files (.kt).
 * Uses the Kotlin PSI API (bundled with IntelliJ IDEA as the Kotlin plugin).
 * Handles data classes, sealed classes, companion objects, Spring and Ktor annotations.
 */
class KotlinLanguageScanner(project: Project) : LanguageScanner(project) {

    override val extensions = setOf("kt")

    // fqName → KtClassOrObject, populated during collectFile, cleared after resolveDependencies
    private val classMap = mutableMapOf<String, KtClassOrObject>()

    override fun collectFile(
        vFile: VirtualFile,
        nodes: MutableList<FileNode>,
        projectClasses: MutableSet<String>
    ) {
        val psiManager = PsiManager.getInstance(project)
        val ktFile = psiManager.findFile(vFile) as? KtFile ?: return

        // Package prefix
        val pkg = ktFile.packageFqName.asString()

        for (declaration in ktFile.declarations) {
            when (declaration) {
                is KtClassOrObject -> collectClass(declaration, pkg, vFile, ktFile, nodes, projectClasses)
                // Top-level functions become a synthetic "module" node
                is KtNamedFunction -> collectTopLevelFunction(declaration, pkg, vFile, ktFile, nodes, projectClasses)
                else -> Unit
            }
        }
    }

    private fun collectClass(
        ktClass: KtClassOrObject,
        pkg: String,
        vFile: VirtualFile,
        ktFile: KtFile,
        nodes: MutableList<FileNode>,
        projectClasses: MutableSet<String>
    ) {
        val simpleName = ktClass.name ?: return
        val qName = if (pkg.isEmpty()) simpleName else "$pkg.$simpleName"

        projectClasses.add(qName)
        classMap[qName] = ktClass

        val annotations = ktClass.annotationEntries.mapNotNull { ann ->
            ann.shortName?.asString()
        }
        val type = classifyKotlin(ktClass, annotations)
        val moduleName = qName.substringBeforeLast('.').split('.').take(3).joinToString(".")

        val methods = ktClass.declarations
            .filterIsInstance<KtNamedFunction>()
            .map { it.name ?: "<anonymous>" }

        nodes.add(FileNode(
            id = qName,
            name = simpleName,
            qualifiedName = qName,
            path = vFile.path,
            type = type,
            module = moduleName,
            lines = ktFile.text.lines().size,
            methods = methods,
            annotations = annotations
        ))

        // Recurse into nested classes
        for (nested in ktClass.declarations.filterIsInstance<KtClassOrObject>()) {
            collectClass(nested, qName, vFile, ktFile, nodes, projectClasses)
        }
    }

    private fun collectTopLevelFunction(
        fn: KtNamedFunction,
        pkg: String,
        vFile: VirtualFile,
        ktFile: KtFile,
        nodes: MutableList<FileNode>,
        projectClasses: MutableSet<String>
    ) {
        val simpleName = fn.name ?: return
        // Only emit a node for annotated top-level functions (e.g., Ktor routes @Get, @Post)
        val annotations = fn.annotationEntries.mapNotNull { it.shortName?.asString() }
        val routeAnnotations = setOf("Get", "Post", "Put", "Delete", "Patch", "Route", "Location")
        if (annotations.intersect(routeAnnotations).isEmpty()) return

        val qName = if (pkg.isEmpty()) simpleName else "$pkg.$simpleName"
        projectClasses.add(qName)
        nodes.add(FileNode(
            id = qName,
            name = simpleName,
            qualifiedName = qName,
            path = vFile.path,
            type = NodeType.CONTROLLER,
            module = pkg.split('.').take(3).joinToString("."),
            lines = ktFile.text.lines().size,
            methods = listOf(simpleName),
            annotations = annotations
        ))
    }

    override fun resolveDependencies(
        projectClasses: Set<String>,
        edges: MutableList<DependencyEdge>
    ) {
        for ((qName, ktClass) in classMap) {
            // Supertypes (extends + implements in one list in Kotlin)
            for (superTypeEntry in ktClass.superTypeListEntries) {
                val typeRef = superTypeEntry.typeReference ?: continue
                val superName = typeRef.text
                    .substringBefore('<')   // strip generics like BaseEntity<Long>
                    .trim()

                // Try to find by simple name within project classes
                val resolved = resolveSimpleName(superName, qName, projectClasses)
                if (resolved != null) {
                    // Distinguish class vs interface: KtSuperTypeCallEntry = extends (has constructor call)
                    val edgeType = if (superTypeEntry is KtSuperTypeCallEntry) EdgeType.EXTENDS else EdgeType.IMPLEMENTS
                    edges.add(DependencyEdge(qName, resolved, edgeType))
                }

                // Also resolve generic type args mentioned in the supertype
                extractGenericsFromText(typeRef.text).forEach { typeName ->
                    resolveSimpleName(typeName, qName, projectClasses)?.let { resolved2 ->
                        if (resolved2 != qName) edges.add(DependencyEdge(qName, resolved2, EdgeType.USES))
                    }
                }
            }

            // Properties (fields)
            for (prop in ktClass.declarations.filterIsInstance<KtProperty>()) {
                val typeText = prop.typeReference?.text ?: continue
                val isInjected = prop.annotationEntries.any {
                    it.shortName?.asString() in INJECTION_ANNOTATIONS
                }
                resolveTypeText(typeText, qName, projectClasses).forEach { resolved ->
                    edges.add(DependencyEdge(qName, resolved, if (isInjected) EdgeType.INJECTS else EdgeType.USES))
                }
            }

            // Constructor parameters
            for (param in (ktClass as? KtClass)?.primaryConstructorParameters ?: emptyList()) {
                val typeText = param.typeReference?.text ?: continue
                val isInjected = param.annotationEntries.any {
                    it.shortName?.asString() in INJECTION_ANNOTATIONS
                }
                // Single-constructor injection (Spring)
                val isSingleCtor = (ktClass as? KtClass)?.secondaryConstructors?.isEmpty() == true
                resolveTypeText(typeText, qName, projectClasses).forEach { resolved ->
                    edges.add(DependencyEdge(qName, resolved,
                        if (isInjected || isSingleCtor) EdgeType.INJECTS else EdgeType.USES))
                }
            }

            // Method return types and parameters
            for (fn in ktClass.declarations.filterIsInstance<KtNamedFunction>()) {
                fn.typeReference?.text?.let { ret ->
                    resolveTypeText(ret, qName, projectClasses).forEach { resolved ->
                        edges.add(DependencyEdge(qName, resolved, EdgeType.USES))
                    }
                }
                for (param in fn.valueParameters) {
                    val typeText = param.typeReference?.text ?: continue
                    resolveTypeText(typeText, qName, projectClasses).forEach { resolved ->
                        edges.add(DependencyEdge(qName, resolved, EdgeType.USES))
                    }
                }
            }
        }
        classMap.clear()
    }

    // ---- Helpers ---------------------------------------------------------------

    /** Resolve a simple/short class name to a qualified name in projectClasses. */
    private fun resolveSimpleName(simpleName: String, currentQName: String, projectClasses: Set<String>): String? {
        if (simpleName in projectClasses) return simpleName
        // Try matching by suffix (e.g., "BaseEntity" matches "com.example.entity.BaseEntity")
        return projectClasses.firstOrNull { it.substringAfterLast('.') == simpleName && it != currentQName }
    }

    /** Parse type reference text (including generics) and resolve all project class references. */
    private fun resolveTypeText(typeText: String, currentQName: String, projectClasses: Set<String>): List<String> {
        val result = mutableListOf<String>()
        // Extract raw type + all generic args from "Map<String, Pet>", "List<Owner>", etc.
        val typeNames = mutableListOf(typeText.substringBefore('<').trim())
        typeNames.addAll(extractGenericsFromText(typeText))

        for (name in typeNames) {
            val clean = name.trim().trimEnd('?') // remove Kotlin nullable '?'
            resolveSimpleName(clean, currentQName, projectClasses)?.let {
                if (it != currentQName) result.add(it)
            }
        }
        return result
    }

    /** Extract all type names from inside <...> brackets recursively. */
    private fun extractGenericsFromText(text: String): List<String> {
        val result = mutableListOf<String>()
        val inner = text.substringAfter('<', "").substringBeforeLast('>', "")
        if (inner.isBlank()) return result
        // Split on commas, but respect nested angle brackets
        var depth = 0
        val current = StringBuilder()
        for (ch in inner) {
            when (ch) {
                '<' -> { depth++; current.append(ch) }
                '>' -> { depth--; current.append(ch) }
                ',' -> if (depth == 0) {
                    result.add(current.toString().substringBefore('<').trim())
                    result.addAll(extractGenericsFromText(current.toString()))
                    current.clear()
                } else current.append(ch)
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) {
            result.add(current.toString().substringBefore('<').trim())
            result.addAll(extractGenericsFromText(current.toString()))
        }
        return result
    }

    private fun classifyKotlin(ktClass: KtClassOrObject, annotations: List<String>): NodeType {
        val a = annotations.toSet()
        val name = ktClass.name ?: ""
        return when {
            // Spring Boot entry point
            a.intersect(ENTRY_ANNOTATIONS).isNotEmpty() -> NodeType.ENTRY_POINT
            // main() top-level function is handled separately
            a.intersect(CONTROLLER_ANNOTATIONS).isNotEmpty() -> NodeType.CONTROLLER
            a.contains("Service") || a.contains("Component") || a.contains("Injectable") -> NodeType.SERVICE
            a.intersect(REPOSITORY_ANNOTATIONS).isNotEmpty() -> NodeType.REPOSITORY
            a.intersect(ENTITY_ANNOTATIONS).isNotEmpty() -> NodeType.ENTITY
            a.intersect(CONFIG_ANNOTATIONS).isNotEmpty() -> NodeType.CONFIG
            (ktClass as? KtClass)?.isInterface() == true -> NodeType.INTERFACE
            (ktClass as? KtClass)?.isEnum() == true -> NodeType.ENUM
            (ktClass as? KtClass)?.isData() == true -> NodeType.MODEL
            (ktClass as? KtClass)?.isSealed() == true -> NodeType.MODEL
            ktClass is KtObjectDeclaration -> NodeType.CONFIG  // Kotlin objects are often config/singletons
            else -> categoriseByName(name)
        }
    }
}
