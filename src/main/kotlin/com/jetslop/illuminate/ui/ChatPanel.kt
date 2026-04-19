package com.jetslop.illuminate.ui

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.jetslop.illuminate.ai.AIService
import com.jetslop.illuminate.model.ProjectStructure
import com.jetslop.illuminate.services.IlluminateService
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent

class ChatPanel(private val project: Project) {

    private val gson = Gson()
    private val browser: JBCefBrowser = JBCefBrowser()
    private val askQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val navigateQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val clearQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var currentStructure: ProjectStructure? = null
    private var pageLoaded = false

    val component: JComponent get() = browser.component

    init {
        navigateQuery.addHandler { filePath ->
            ApplicationManager.getApplication().invokeLater {
                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                if (vFile != null) {
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }
            }
            JBCefJSQuery.Response("ok")
        }

        clearQuery.addHandler {
            IlluminateService.getInstance(project).clearChatHistory()
            JBCefJSQuery.Response("ok")
        }

        askQuery.addHandler { question ->
            val structure = currentStructure
            if (structure == null) {
                val errorMsg = "Please scan the project first by clicking 'Illuminate Project'."
                sendAnswer(errorMsg)
                IlluminateService.getInstance(project).addChatMessage("bot", errorMsg)
                return@addHandler JBCefJSQuery.Response("ok")
            }

            // Save user message
            IlluminateService.getInstance(project).addChatMessage("user", question)

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val answer = AIService.getInstance().chatAboutProject(question, structure)
                    ApplicationManager.getApplication().invokeLater {
                        sendAnswer(answer)
                        IlluminateService.getInstance(project).addChatMessage("bot", answer)
                    }
                } catch (e: Exception) {
                    val errorMsg = "Error: ${e.message}"
                    ApplicationManager.getApplication().invokeLater {
                        sendAnswer(errorMsg)
                        IlluminateService.getInstance(project).addChatMessage("bot", errorMsg)
                    }
                }
            }
            JBCefJSQuery.Response("ok")
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    pageLoaded = true
                    injectFunctions(cefBrowser)
                    restoreChatHistory(cefBrowser)
                }
            }
        }, browser.cefBrowser)

        val html = javaClass.getResource("/web/chat.html")?.readText() ?: "<html><body>Error loading chat</body></html>"
        browser.loadHTML(html)
    }

    fun onProjectScanned(structure: ProjectStructure) {
        currentStructure = structure
    }

    private fun injectFunctions(cefBrowser: CefBrowser) {
        val js = """
            window.askQuestion = function(question) {
                ${askQuery.inject("question")}
            };
            window.navigateToFile = function(path) {
                ${navigateQuery.inject("path")}
            };
            window.clearHistory = function() {
                ${clearQuery.inject("'clear'")}
            };
        """.trimIndent()
        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
    }

    private fun restoreChatHistory(cefBrowser: CefBrowser) {
        val history = IlluminateService.getInstance(project).chatHistory
        if (history.isNotEmpty()) {
            val json = gson.toJson(history.map { mapOf("role" to it.role, "content" to it.content) })
            cefBrowser.executeJavaScript("restoreHistory($json);", cefBrowser.url, 0)
        }
    }

    private fun sendAnswer(text: String) {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")
        browser.cefBrowser.executeJavaScript("receiveAnswer(\"$escaped\");", browser.cefBrowser.url, 0)
    }
}
