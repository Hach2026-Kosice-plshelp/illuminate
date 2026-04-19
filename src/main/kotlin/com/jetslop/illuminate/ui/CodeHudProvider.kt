package com.jetslop.illuminate.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.jetslop.illuminate.ai.AIService
import com.jetslop.illuminate.model.FileNode
import com.jetslop.illuminate.model.NodeType
import com.jetslop.illuminate.model.ProjectStructure
import com.jetslop.illuminate.services.IlluminateService
import com.jetslop.illuminate.settings.IlluminateSettings
import org.jetbrains.kotlin.psi.*
import java.awt.Color
import java.awt.Point

class CodeHudProvider : ProjectActivity {

    companion object {
        private val hudInlays = mutableMapOf<Editor, MutableList<Inlay<*>>>()
        private val editorListeners = mutableSetOf<Editor>()

        private val TYPE_COLORS = mapOf(
            NodeType.ENTRY_POINT to Color(239, 68, 68),
            NodeType.CONTROLLER to Color(59, 130, 246),
            NodeType.SERVICE to Color(168, 85, 247),
            NodeType.REPOSITORY to Color(20, 184, 166),
            NodeType.MODEL to Color(34, 197, 94),
            NodeType.ENTITY to Color(16, 185, 129),
            NodeType.CONFIG to Color(99, 102, 241),
            NodeType.UTIL to Color(245, 158, 11),
            NodeType.INTERFACE to Color(6, 182, 212),
            NodeType.ENUM to Color(139, 92, 246),
            NodeType.TEST to Color(100, 116, 139),
            NodeType.OTHER to Color(148, 163, 184)
        )
    }

    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    scheduleHudUpdate(project, source, file)
                }
            }
        )

        IlluminateService.getInstance(project).addListener {
            val service = IlluminateService.getInstance(project)
            if (service.structure != null && !service.isScanning) {
                ApplicationManager.getApplication().invokeLater {
                    val fem = FileEditorManager.getInstance(project)
                    for (file in fem.openFiles) {
                        scheduleHudUpdate(project, fem, file)
                    }
                }
            }
        }
    }

    private fun scheduleHudUpdate(project: Project, fem: FileEditorManager, file: VirtualFile) {
        val service = IlluminateService.getInstance(project)
        val structure = service.structure ?: return

        val editor = fem.getSelectedEditor(file)
        val textEditor = (editor as? TextEditor) ?: return
        val ed = textEditor.editor

        val node = structure.nodes.find { it.path == file.path } ?: return

        val incomingDeps = structure.edges.filter { it.target == node.id }.map { it.source.substringAfterLast('.') }
        val outgoingDeps = structure.edges.filter { it.source == node.id }.map { it.target.substringAfterLast('.') }

        // Install click listener once per editor
        if (ed !in editorListeners) {
            editorListeners.add(ed)
            ed.addEditorMouseListener(object : EditorMouseListener {
                override fun mouseClicked(event: EditorMouseEvent) {
                    val point = event.mouseEvent.point
                    val inlays = hudInlays[ed] ?: return
                    for (inlay in inlays) {
                        if (!inlay.isValid) continue
                        val renderer = inlay.renderer
                        if (renderer is HudRenderer && renderer.onClick != null) {
                            val bounds = inlay.bounds ?: continue
                            if (bounds.contains(point)) {
                                renderer.onClick.invoke()
                                break
                            }
                        }
                    }
                }
            })
        }

        ApplicationManager.getApplication().invokeLater {
            clearHuds(ed)
            addHudsForFile(project, ed, file, node, incomingDeps, outgoingDeps, structure)
        }
    }

    private fun clearHuds(editor: Editor) {
        hudInlays[editor]?.forEach { if (it.isValid) it.dispose() }
        hudInlays.remove(editor)
    }

    private data class InlaySlot(
        val offset: Int,
        val inlay: Inlay<*>,
        val aiKey: String // "class:QName" or "method:QName.methodName"
    )

    private fun addHudsForFile(
        project: Project,
        editor: Editor,
        file: VirtualFile,
        node: FileNode,
        incomingDeps: List<String>,
        outgoingDeps: List<String>,
        structure: ProjectStructure
    ) {
        val inlays = mutableListOf<Inlay<*>>()
        val slots = mutableListOf<InlaySlot>()
        val color = TYPE_COLORS[node.type] ?: Color(148, 163, 184)

        val depSummary = buildString {
            if (incomingDeps.isNotEmpty()) append("← ${incomingDeps.size} deps in")
            if (incomingDeps.isNotEmpty() && outgoingDeps.isNotEmpty()) append("  ·  ")
            if (outgoingDeps.isNotEmpty()) append("→ ${outgoingDeps.size} deps out")
            if (isEmpty()) append("No dependencies")
        }

        val props = InlayProperties().apply {
            relatesToPrecedingText(true)
            priority(1000)
            disableSoftWrapping(true)
        }

        val psiFile = ReadAction.compute<PsiFile?, Throwable> {
            PsiManager.getInstance(project).findFile(file)
        } ?: return

        // Unified method info — works for both Java and Kotlin
        data class MethodInfo(
            val name: String,
            val offset: Int,
            val signature: String,
            val body: String,
            val staticDesc: MethodHudInfo?
        )
        data class ClassInfo(
            val qualifiedName: String?,
            val offset: Int,
            val methods: List<MethodInfo>
        )

        val classInfoList: List<ClassInfo> = when (psiFile) {
            is PsiJavaFile -> ReadAction.compute<List<ClassInfo>, Throwable> {
                psiFile.classes.map { psiClass ->
                    val methods = psiClass.methods.map { method ->
                        val sig = "${method.returnType?.presentableText ?: "void"} ${method.name}(${
                            method.parameterList.parameters.joinToString(", ") { "${it.type.presentableText} ${it.name}" }
                        })"
                        val bodyText = method.body?.text ?: ""
                        MethodInfo(
                            name = method.name,
                            offset = method.textOffset,
                            signature = sig,
                            body = bodyText,
                            staticDesc = buildMethodDescription(method, node)
                        )
                    }
                    ClassInfo(psiClass.qualifiedName, psiClass.textOffset, methods)
                }
            }
            is KtFile -> ReadAction.compute<List<ClassInfo>, Throwable> {
                psiFile.declarations.filterIsInstance<KtClassOrObject>().map { ktClass ->
                    val pkg = psiFile.packageFqName.asString()
                    val simpleName = ktClass.name ?: ""
                    val qName = if (pkg.isEmpty()) simpleName else "$pkg.$simpleName"
                    val methods = ktClass.declarations.filterIsInstance<KtNamedFunction>().map { fn ->
                        val params = fn.valueParameters.joinToString(", ") {
                            "${it.name ?: "_"}: ${it.typeReference?.text ?: "Any"}"
                        }
                        val retType = fn.typeReference?.text ?: "Unit"
                        val sig = "fun ${fn.name}($params): $retType"
                        val bodyText = fn.bodyExpression?.text ?: fn.bodyBlockExpression?.text ?: ""
                        MethodInfo(
                            name = fn.name ?: "<anonymous>",
                            offset = fn.textOffset,
                            signature = sig,
                            body = bodyText,
                            staticDesc = buildKotlinMethodDescription(fn, node)
                        )
                    }
                    ClassInfo(qName, ktClass.textOffset, methods)
                }
            }
            else -> return
        }

        for (classInfo in classInfoList) {
            val classOffset = classInfo.offset
            val classLine = editor.document.getLineNumber(classOffset)
            val lineStart = editor.document.getLineStartOffset(classLine)
            val classQName = classInfo.qualifiedName ?: ""

            // Class-level HUD — static first
            val classHud = HudRenderer(
                icon = iconForType(node.type),
                label = node.type.label,
                description = depSummary,
                accentColor = color,
                tag = node.module.substringAfterLast('.'),
                onClick = { HudDetailPopup.showForClass(editor, node, structure) }
            )
            editor.inlayModel.addBlockElement(lineStart, props, classHud)?.let {
                inlays.add(it)
                slots.add(InlaySlot(lineStart, it, "class:${node.id}"))
            }

            // Method-level HUDs
            for (mi in classInfo.methods) {
                val methodLine = editor.document.getLineNumber(mi.offset)
                val methodLineStart = editor.document.getLineStartOffset(methodLine)

                val desc = mi.staticDesc
                if (desc != null) {
                    val capturedMi = mi
                    val methodHud = HudRenderer(
                        icon = desc.icon,
                        label = mi.name,
                        description = desc.description,
                        accentColor = desc.color ?: color,
                        tag = desc.tag,
                        onClick = {
                            HudDetailPopup.showForMethod(
                                editor, classQName, capturedMi.name, capturedMi.signature,
                                capturedMi.body, node, structure
                            )
                        }
                    )
                    editor.inlayModel.addBlockElement(methodLineStart, props, methodHud)?.let {
                        inlays.add(it)
                        slots.add(InlaySlot(methodLineStart, it, "method:$classQName.${mi.name}"))
                    }
                }
            }

            // Now fire async AI explanations if enabled
            val settings = IlluminateSettings.getInstance()
            if (settings.enableAiHud && settings.apiKey.isNotBlank()) {
                if (classQName.isBlank()) continue
                val methods = classInfo.methods

                ApplicationManager.getApplication().executeOnPooledThread {
                    // AI class explanation
                    val classExplanation = AIService.getInstance().explainClassForHud(node, structure)
                    if (classExplanation != null) {
                        ApplicationManager.getApplication().invokeLater {
                            val slot = slots.find { it.aiKey == "class:${node.id}" }
                            if (slot != null && slot.inlay.isValid) {
                                slot.inlay.dispose()
                                val aiHud = HudRenderer(
                                    icon = "🧠",
                                    label = node.type.label,
                                    description = classExplanation,
                                    accentColor = color,
                                    tag = "AI",
                                    onClick = { HudDetailPopup.showForClass(editor, node, structure) }
                                )
                                editor.inlayModel.addBlockElement(slot.offset, props, aiHud)?.let { newInlay ->
                                    inlays.add(newInlay)
                                }
                            }
                        }
                    }

                    // AI method explanations
                    for (mi in methods) {
                        if (mi.staticDesc == null) continue
                        val methodExplanation = AIService.getInstance().explainMethodForHud(
                            className = classQName,
                            methodName = mi.name,
                            methodSignature = mi.signature,
                            methodBody = mi.body,
                            node = node,
                            structure = structure
                        )
                        if (methodExplanation != null) {
                            ApplicationManager.getApplication().invokeLater {
                                val slot = slots.find { it.aiKey == "method:$classQName.${mi.name}" }
                                if (slot != null && slot.inlay.isValid) {
                                    slot.inlay.dispose()
                                    val methodLine = editor.document.getLineNumber(mi.offset)
                                    val methodLineStart = editor.document.getLineStartOffset(methodLine)
                                    val aiHud = HudRenderer(
                                        icon = "🧠",
                                        label = mi.name,
                                        description = methodExplanation,
                                        accentColor = mi.staticDesc.color ?: color,
                                        tag = "AI",
                                        onClick = {
                                            HudDetailPopup.showForMethod(
                                                editor, classQName, mi.name, mi.signature,
                                                mi.body, node, structure
                                            )
                                        }
                                    )
                                    editor.inlayModel.addBlockElement(methodLineStart, props, aiHud)?.let { newInlay ->
                                        inlays.add(newInlay)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (inlays.isNotEmpty()) {
            hudInlays[editor] = inlays
        }
    }

    private data class MethodHudInfo(
        val icon: String,
        val description: String,
        val color: Color?,
        val tag: String?
    )

    private fun buildKotlinMethodDescription(fn: KtNamedFunction, node: FileNode): MethodHudInfo? {
        val annotations = fn.annotationEntries.mapNotNull { it.shortName?.asString() }
        val params = fn.valueParameters

        for (ann in annotations) {
            when (ann) {
                "GetMapping", "RequestMapping" -> return MethodHudInfo(
                    "🌐", "GET endpoint · ${params.size} params",
                    Color(59, 130, 246), "GET"
                )
                "PostMapping" -> return MethodHudInfo(
                    "🌐", "POST endpoint · ${params.size} params",
                    Color(34, 197, 94), "POST"
                )
                "PutMapping" -> return MethodHudInfo(
                    "🌐", "PUT endpoint · ${params.size} params",
                    Color(245, 158, 11), "PUT"
                )
                "DeleteMapping" -> return MethodHudInfo(
                    "🌐", "DELETE endpoint · ${params.size} params",
                    Color(239, 68, 68), "DELETE"
                )
                "PatchMapping" -> return MethodHudInfo(
                    "🌐", "PATCH endpoint · ${params.size} params",
                    Color(168, 85, 247), "PATCH"
                )
            }
        }

        if (annotations.contains("Transactional")) {
            return MethodHudInfo("🔒", "Transactional · ${params.size} params", Color(245, 158, 11), "TX")
        }
        if (annotations.contains("Bean")) {
            val retType = fn.typeReference?.text ?: "object"
            return MethodHudInfo("🏭", "Bean factory · creates $retType", Color(99, 102, 241), "Bean")
        }
        if (annotations.contains("EventListener") || annotations.contains("Scheduled")) {
            return MethodHudInfo(
                "📡", if (annotations.contains("Scheduled")) "Scheduled task" else "Event listener",
                Color(168, 85, 247), if (annotations.contains("Scheduled")) "CRON" else "Event"
            )
        }
        if (fn.name == "main") {
            return MethodHudInfo("🚀", "Application entry point", Color(239, 68, 68), "MAIN")
        }
        val isPublic = !fn.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) &&
                !fn.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD) &&
                !fn.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD)
        if (isPublic && params.size >= 3) {
            return MethodHudInfo("⚡", "${params.size} parameters · complex method", Color(245, 158, 11), null)
        }
        if (node.type in listOf(NodeType.SERVICE, NodeType.CONTROLLER, NodeType.REPOSITORY) && isPublic) {
            val retType = fn.typeReference?.text ?: "Unit"
            return MethodHudInfo("⚡", "${params.size} params → $retType", null, null)
        }

        return null
    }

    private fun buildMethodDescription(method: PsiMethod, node: FileNode): MethodHudInfo? {
        val annotations = method.annotations.mapNotNull { it.qualifiedName?.substringAfterLast('.') }
        val params = method.parameterList.parameters

        for (ann in annotations) {
            when (ann) {
                "GetMapping", "RequestMapping" -> return MethodHudInfo(
                    "🌐", "GET endpoint · ${params.size} params",
                    Color(59, 130, 246), "GET"
                )
                "PostMapping" -> return MethodHudInfo(
                    "🌐", "POST endpoint · ${params.size} params",
                    Color(34, 197, 94), "POST"
                )
                "PutMapping" -> return MethodHudInfo(
                    "🌐", "PUT endpoint · ${params.size} params",
                    Color(245, 158, 11), "PUT"
                )
                "DeleteMapping" -> return MethodHudInfo(
                    "🌐", "DELETE endpoint · ${params.size} params",
                    Color(239, 68, 68), "DELETE"
                )
                "PatchMapping" -> return MethodHudInfo(
                    "🌐", "PATCH endpoint · ${params.size} params",
                    Color(168, 85, 247), "PATCH"
                )
            }
        }

        if (annotations.contains("Transactional")) {
            return MethodHudInfo("🔒", "Transactional · ${params.size} params", Color(245, 158, 11), "TX")
        }
        if (annotations.contains("Override")) {
            return MethodHudInfo("🔄", "Overrides parent · ${params.size} params", null, "Override")
        }
        if (method.name == "main" && method.hasModifierProperty(PsiModifier.STATIC)) {
            return MethodHudInfo("🚀", "Application entry point", Color(239, 68, 68), "MAIN")
        }
        if (annotations.contains("Bean")) {
            return MethodHudInfo("🏭", "Bean factory · creates ${method.returnType?.presentableText ?: "object"}", Color(99, 102, 241), "Bean")
        }
        if (annotations.contains("EventListener") || annotations.contains("Scheduled")) {
            return MethodHudInfo(
                "📡", if (annotations.contains("Scheduled")) "Scheduled task" else "Event listener",
                Color(168, 85, 247), if (annotations.contains("Scheduled")) "CRON" else "Event"
            )
        }
        if (method.hasModifierProperty(PsiModifier.PUBLIC) && params.size >= 3) {
            return MethodHudInfo("⚡", "${params.size} parameters · complex method", Color(245, 158, 11), null)
        }
        if (node.type in listOf(NodeType.SERVICE, NodeType.CONTROLLER, NodeType.REPOSITORY) &&
            method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor) {
            val retType = method.returnType?.presentableText ?: "void"
            return MethodHudInfo("⚡", "${params.size} params → $retType", null, null)
        }

        return null
    }

    private fun iconForType(type: NodeType): String = when (type) {
        NodeType.ENTRY_POINT -> "🚀"
        NodeType.CONTROLLER -> "🌐"
        NodeType.SERVICE -> "⚡"
        NodeType.REPOSITORY -> "💾"
        NodeType.MODEL -> "📋"
        NodeType.ENTITY -> "📦"
        NodeType.CONFIG -> "⚙️"
        NodeType.UTIL -> "🔧"
        NodeType.INTERFACE -> "📐"
        NodeType.ENUM -> "🏷️"
        NodeType.TEST -> "🧪"
        NodeType.OTHER -> "📄"
    }
}
