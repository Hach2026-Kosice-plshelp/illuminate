package com.jetslop.illuminate.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.jetslop.illuminate.ai.AIService
import com.jetslop.illuminate.model.FileNode
import com.jetslop.illuminate.model.ProjectStructure
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

object HudDetailPopup {

    fun showForClass(
        editor: Editor,
        node: FileNode,
        structure: ProjectStructure
    ) {
        val panel = createLoadingPanel("🧠 Analyzing ${node.name}...")
        val popup = createPopup(panel, "Illuminate · ${node.name}")
        popup.showInBestPositionFor(editor)

        ApplicationManager.getApplication().executeOnPooledThread {
            val explanation = AIService.getInstance().deepExplainClass(node, structure)
            ApplicationManager.getApplication().invokeLater {
                if (!popup.isDisposed) {
                    updatePanel(panel, node.name, node.type.label, explanation, node, structure)
                }
            }
        }
    }

    fun showForMethod(
        editor: Editor,
        className: String,
        methodName: String,
        methodSignature: String,
        methodBody: String,
        node: FileNode,
        structure: ProjectStructure
    ) {
        val panel = createLoadingPanel("🧠 Analyzing $methodName()...")
        val popup = createPopup(panel, "Illuminate · $methodName()")
        popup.showInBestPositionFor(editor)

        ApplicationManager.getApplication().executeOnPooledThread {
            val explanation = AIService.getInstance().deepExplainMethod(
                className, methodName, methodSignature, methodBody, node, structure
            )
            ApplicationManager.getApplication().invokeLater {
                if (!popup.isDisposed) {
                    updatePanel(panel, methodName, "Method in $className", explanation, node, structure)
                }
            }
        }
    }

    private fun createPopup(panel: JPanel, title: String): JBPopup {
        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)
            .createPopup()
    }

    private fun createLoadingPanel(text: String): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(520, 340)
        panel.background = JBColor(Color(13, 17, 23), Color(13, 17, 23))
        panel.border = EmptyBorder(20, 20, 20, 20)

        val loadingLabel = JLabel(text, SwingConstants.CENTER)
        loadingLabel.font = loadingLabel.font.deriveFont(14f)
        loadingLabel.foreground = JBColor(Color(139, 148, 158), Color(139, 148, 158))
        panel.add(loadingLabel, BorderLayout.CENTER)

        return panel
    }

    private fun updatePanel(
        panel: JPanel,
        name: String,
        subtitle: String,
        explanation: String,
        node: FileNode,
        structure: ProjectStructure
    ) {
        panel.removeAll()
        panel.layout = BorderLayout()

        // Header
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor(Color(22, 27, 34), Color(22, 27, 34))
            border = EmptyBorder(14, 16, 14, 16)
        }

        val titleLabel = JLabel("🧠 $name")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        titleLabel.foreground = JBColor(Color(230, 237, 243), Color(230, 237, 243))
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        header.add(titleLabel)

        header.add(Box.createVerticalStrut(4))

        val subtitleLabel = JLabel(subtitle)
        subtitleLabel.font = subtitleLabel.font.deriveFont(12f)
        subtitleLabel.foreground = JBColor(Color(124, 58, 237), Color(124, 58, 237))
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        header.add(subtitleLabel)

        panel.add(header, BorderLayout.NORTH)

        // Content — scrollable HTML
        val deps = structure.edges.filter { it.source == node.id }
            .map { it.target.substringAfterLast('.') }
        val usedBy = structure.edges.filter { it.target == node.id }
            .map { it.source.substringAfterLast('.') }

        val htmlContent = buildString {
            append("<html><head><style>")
            append("body { font-family: sans-serif; font-size: 12px; ")
            append("color: #e6edf3; background: #0d1117; margin: 0; padding: 14px; }")
            append("h3 { color: #a855f7; font-size: 13px; margin-top: 14px; margin-bottom: 6px; }")
            append("p { margin-top: 4px; margin-bottom: 4px; }")
            append("code { background: #1c2333; padding: 1px 4px; font-size: 11px; }")
            append("pre { background: #0a0e14; padding: 10px; font-size: 11px; }")
            append("ul { padding-left: 18px; margin-top: 4px; margin-bottom: 4px; }")
            append("li { margin-bottom: 3px; }")
            append("strong { color: #7c3aed; }")
            append("table { width: 100%; }")
            append("td { vertical-align: top; padding: 2px 6px; }")
            append(".section { background: #161b22; border: 1px solid #30363d; padding: 12px; margin-top: 8px; margin-bottom: 8px; }")
            append("</style></head><body>")

            // AI explanation
            append("<div class='section'>")
            append("<h3>&#128214; Deep Explanation</h3>")
            append(markdownToHtml(explanation))
            append("</div>")

            // Dependencies
            if (deps.isNotEmpty() || usedBy.isNotEmpty()) {
                append("<div class='section'>")
                append("<h3>&#128279; Dependencies</h3>")
                if (deps.isNotEmpty()) {
                    append("<p><strong>Depends on:</strong></p><p>")
                    for (d in deps) {
                        append("<code>&rarr; $d</code> &nbsp; ")
                    }
                    append("</p>")
                }
                if (usedBy.isNotEmpty()) {
                    append("<p><strong>Used by:</strong></p><p>")
                    for (u in usedBy) {
                        append("<code>&larr; $u</code> &nbsp; ")
                    }
                    append("</p>")
                }
                append("</div>")
            }

            // Context
            append("<div class='section'>")
            append("<h3>&#128205; Context</h3>")
            append("<p><strong>Module:</strong> ${node.module}</p>")
            append("<p><strong>Type:</strong> ${node.type.label}</p>")
            append("<p><strong>Methods:</strong> ${node.methods.take(10).joinToString(", ")}")
            if (node.methods.size > 10) append(" + ${node.methods.size - 10} more")
            append("</p>")
            if (node.annotations.isNotEmpty()) {
                append("<p><strong>Annotations:</strong> ${node.annotations.joinToString(", ")}</p>")
            }
            append("</div>")

            append("</body></html>")
        }

        val editorPane = JEditorPane()
        editorPane.contentType = "text/html"
        editorPane.isEditable = false
        editorPane.background = JBColor(Color(13, 17, 23), Color(13, 17, 23))
        editorPane.border = EmptyBorder(0, 0, 0, 0)
        editorPane.text = htmlContent

        val scrollPane = JScrollPane(editorPane)
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.background = JBColor(Color(13, 17, 23), Color(13, 17, 23))
        scrollPane.viewport.background = JBColor(Color(13, 17, 23), Color(13, 17, 23))
        scrollPane.verticalScrollBar.unitIncrement = 14

        panel.add(scrollPane, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    private fun markdownToHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("```\\w*\\n([\\s\\S]*?)```")) { "<pre><code>${it.groupValues[1]}</code></pre>" }
            .replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
            .replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
            .replace(Regex("\\*([^*]+)\\*")) { "<em>${it.groupValues[1]}</em>" }
            .replace(Regex("^### (.+)$", RegexOption.MULTILINE)) { "<h3>${it.groupValues[1]}</h3>" }
            .replace(Regex("^- (.+)$", RegexOption.MULTILINE)) { "<li>${it.groupValues[1]}</li>" }
            .replace(Regex("(<li>.*</li>)", RegexOption.DOT_MATCHES_ALL)) { "<ul>${it.groupValues[1]}</ul>" }
            .replace("\n", "<br>")
    }
}
