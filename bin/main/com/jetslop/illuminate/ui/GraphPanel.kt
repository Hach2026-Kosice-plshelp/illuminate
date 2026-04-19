package com.jetslop.illuminate.ui

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.jetslop.illuminate.model.ProjectStructure
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

class GraphPanel(private val project: Project) {

    private val gson = Gson()
    private val browser: JBCefBrowser = JBCefBrowser()
    private val navigateQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var pendingData: ProjectStructure? = null
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

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    pageLoaded = true
                    injectNavigateFunction(cefBrowser)
                    pendingData?.let { injectGraphData(cefBrowser, it) }
                    pendingData = null
                }
            }
        }, browser.cefBrowser)

        val html = javaClass.getResource("/web/graph.html")?.readText() ?: "<html><body>Error loading graph</body></html>"
        browser.loadHTML(html)

        // Claim focus on click so key events go to this component
        browser.component.isFocusable = true
        browser.component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                browser.component.requestFocusInWindow()
            }
        })

        // Forward keyboard events to JS via executeJavaScript.
        // JCEF inside IntelliJ tool windows does not reliably deliver native key events
        // to Chromium even when focused — intercept at Swing level and dispatch to JS.
        browser.component.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!pageLoaded) return
                val jsKey = when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> "Escape"
                    KeyEvent.VK_ENTER  -> "Enter"
                    else -> {
                        val c = e.keyChar
                        if (c == KeyEvent.CHAR_UNDEFINED || c.code < 32) return
                        c.toString()
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                    }
                }
                browser.cefBrowser.executeJavaScript(
                    "if(window._handleHotKey) window._handleHotKey('$jsKey');",
                    browser.cefBrowser.url, 0
                )
            }
        })
    }

    fun updateGraph(structure: ProjectStructure) {
        if (pageLoaded) {
            injectGraphData(browser.cefBrowser, structure)
        } else {
            pendingData = structure
        }
    }

    fun showLoading() {
        if (pageLoaded) {
            browser.cefBrowser.executeJavaScript("showLoading();", browser.cefBrowser.url, 0)
        }
    }

    fun setProgress(phase: String, current: Int, total: Int) {
        if (!pageLoaded) return
        val safePhase = phase.replace("'", "\\'")
        browser.cefBrowser.executeJavaScript(
            "setProgress('$safePhase', $current, $total);",
            browser.cefBrowser.url, 0
        )
    }

    private fun injectNavigateFunction(cefBrowser: CefBrowser) {
        val js = """
            window.navigateToFile = function(path) {
                ${navigateQuery.inject("path")}
            };
        """.trimIndent()
        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
    }

    private fun injectGraphData(cefBrowser: CefBrowser, structure: ProjectStructure) {
        val jsonData = gson.toJson(structure)
        cefBrowser.executeJavaScript("renderGraph($jsonData);", cefBrowser.url, 0)
    }
}
