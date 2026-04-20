package com.jetslop.illuminate.scanner

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.jetslop.illuminate.model.*

class JavaLanguageScanner(project: Project) : LanguageScanner(project) {

    override val extensions = setOf("java")

    // Per-scan state — reset by ProjectScanner before each scan
    private val classMap = mutableMapOf<String, PsiClass>()

    override fun collectFile(
        vFile: VirtualFile,
        nodes: MutableList<FileNode>,
        projectClasses: MutableSet<String>
    ) {
        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(vFile) as? PsiJavaFile ?: return
        for (psiClass in psiFile.classes) {
            val qName = psiClass.qualifiedName ?: continue
            projectClasses.add(qName)
            classMap[qName] = psiClass

            val annotations = psiClass.annotations.mapNotNull {
                it.qualifiedName?.substringAfterLast('.')
            }
            val type = classifyJava(psiClass, annotations)
            val moduleName = qName.substringBeforeLast('.').split('.').take(3).joinToString(".")

            nodes.add(FileNode(
                id = qName,
                name = psiClass.name ?: qName.substringAfterLast('.'),
                qualifiedName = qName,
                path = vFile.path,
                type = type,
                module = moduleName,
                lines = psiFile.text.lines().size,
                methods = psiClass.methods.map { it.name },
                annotations = annotations
            ))
        }
    }

    override fun resolveDependencies(
        projectClasses: Set<String>,
        edges: MutableList<DependencyEdge>
    ) {
        for ((qName, psiClass) in classMap) {
            // Extends
            psiClass.superClass?.qualifiedName?.let { parent ->
                if (parent in projectClasses) edges.add(DependencyEdge(qName, parent, EdgeType.EXTENDS))
            }
            psiClass.extendsList?.referencedTypes?.forEach { refType ->
                for (t in extractProjectTypes(refType, projectClasses)) {
                    if (t != qName && t != psiClass.superClass?.qualifiedName)
                        edges.add(DependencyEdge(qName, t, EdgeType.USES))
                }
            }

            // Implements
            for (iface in psiClass.interfaces) {
                val ifaceName = iface.qualifiedName ?: continue
                if (ifaceName in projectClasses) edges.add(DependencyEdge(qName, ifaceName, EdgeType.IMPLEMENTS))
            }
            psiClass.implementsList?.referencedTypes?.forEach { refType ->
                for (t in extractProjectTypes(refType, projectClasses)) {
                    if (t != qName) edges.add(DependencyEdge(qName, t, EdgeType.USES))
                }
            }

            // Fields
            for (field in psiClass.fields) {
                for (ft in extractProjectTypes(field.type, projectClasses)) {
                    if (ft != qName) {
                        val isInjected = field.annotations.any {
                            it.qualifiedName?.substringAfterLast('.') in INJECTION_ANNOTATIONS
                        }
                        edges.add(DependencyEdge(qName, ft, if (isInjected) EdgeType.INJECTS else EdgeType.USES))
                    }
                }
            }

            // Constructors
            for (ctor in psiClass.constructors) {
                val isInjectedCtor = ctor.annotations.any {
                    it.qualifiedName?.substringAfterLast('.') in INJECTION_ANNOTATIONS
                } || psiClass.constructors.size == 1
                for (param in ctor.parameterList.parameters) {
                    for (pt in extractProjectTypes(param.type, projectClasses)) {
                        if (pt != qName)
                            edges.add(DependencyEdge(qName, pt, if (isInjectedCtor) EdgeType.INJECTS else EdgeType.USES))
                    }
                }
            }

            // Methods
            for (method in psiClass.methods) {
                for (rt in extractProjectTypes(method.returnType, projectClasses)) {
                    if (rt != qName) edges.add(DependencyEdge(qName, rt, EdgeType.USES))
                }
                for (param in method.parameterList.parameters) {
                    for (pt in extractProjectTypes(param.type, projectClasses)) {
                        if (pt != qName) edges.add(DependencyEdge(qName, pt, EdgeType.USES))
                    }
                }
            }

            // Method bodies
            for (method in psiClass.methods + psiClass.constructors) {
                method.body?.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitLocalVariable(v: PsiLocalVariable) {
                        super.visitLocalVariable(v)
                        for (t in extractProjectTypes(v.type, projectClasses)) {
                            if (t != qName) edges.add(DependencyEdge(qName, t, EdgeType.USES))
                        }
                    }
                    override fun visitNewExpression(e: PsiNewExpression) {
                        super.visitNewExpression(e)
                        for (t in extractProjectTypes(e.type, projectClasses)) {
                            if (t != qName) edges.add(DependencyEdge(qName, t, EdgeType.USES))
                        }
                    }
                    override fun visitMethodCallExpression(e: PsiMethodCallExpression) {
                        super.visitMethodCallExpression(e)
                        e.resolveMethod()?.containingClass?.qualifiedName?.let { called ->
                            if (called in projectClasses && called != qName)
                                edges.add(DependencyEdge(qName, called, EdgeType.USES))
                        }
                    }
                })
            }
        }
        classMap.clear()
    }

    private fun extractProjectTypes(type: PsiType?, projectClasses: Set<String>): List<String> {
        val result = mutableListOf<String>()
        if (type == null) return result
        if (type is PsiClassType) {
            type.resolve()?.qualifiedName?.let { if (it in projectClasses) result.add(it) }
            for (arg in type.parameters) result.addAll(extractProjectTypes(arg, projectClasses))
        } else if (type is PsiArrayType) {
            result.addAll(extractProjectTypes(type.componentType, projectClasses))
        }
        return result
    }

    private fun classifyJava(psiClass: PsiClass, annotations: List<String>): NodeType {
        val a = annotations.toSet()
        return when {
            hasMainMethod(psiClass) -> NodeType.ENTRY_POINT
            a.intersect(ENTRY_ANNOTATIONS).isNotEmpty() -> NodeType.ENTRY_POINT
            a.intersect(CONTROLLER_ANNOTATIONS).isNotEmpty() -> NodeType.CONTROLLER
            a.contains("Service") -> NodeType.SERVICE
            a.intersect(REPOSITORY_ANNOTATIONS).isNotEmpty() -> NodeType.REPOSITORY
            a.intersect(ENTITY_ANNOTATIONS).isNotEmpty() -> NodeType.ENTITY
            a.intersect(CONFIG_ANNOTATIONS).isNotEmpty() -> NodeType.CONFIG
            psiClass.isInterface -> NodeType.INTERFACE
            psiClass.isEnum -> NodeType.ENUM
            else -> categoriseByName(psiClass.name ?: "")
        }
    }

    private fun hasMainMethod(psiClass: PsiClass) = psiClass.methods.any { m ->
        m.name == "main" &&
                m.hasModifierProperty(PsiModifier.STATIC) &&
                m.hasModifierProperty(PsiModifier.PUBLIC) &&
                m.parameterList.parameters.size == 1
    }
}
