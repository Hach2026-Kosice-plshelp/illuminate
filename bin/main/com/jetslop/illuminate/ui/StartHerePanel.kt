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
import javax.swing.JComponent

class StartHerePanel(private val project: Project) {

    private val gson = Gson()
    private val browser: JBCefBrowser = JBCefBrowser()
    private val navigateQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val toggleQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
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

        toggleQuery.addHandler { data ->
            // data format: "order:completed" e.g. "3:true"
            val parts = data.split(":")
            if (parts.size == 2) {
                val order = parts[0].toIntOrNull()
                val isCompleted = parts[1] == "true"
                if (order != null) {
                    val service = com.jetslop.illuminate.services.IlluminateService.getInstance(project)
                    if (isCompleted) service.markStartHereCompleted(order)
                    else service.markStartHereUncompleted(order)
                }
            }
            JBCefJSQuery.Response("ok")
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    pageLoaded = true
                    injectNavigateFunction(cefBrowser)
                    injectToggleFunction(cefBrowser)
                    pendingData?.let { injectData(cefBrowser, it) }
                    pendingData = null
                }
            }
        }, browser.cefBrowser)

        val html = javaClass.getResource("/web/starthere.html")?.readText() ?: "<html><body>Error loading</body></html>"
        browser.loadHTML(html)
    }

    fun updateStartHere(structure: ProjectStructure) {
        if (pageLoaded) {
            injectData(browser.cefBrowser, structure)
        } else {
            pendingData = structure
        }
    }

    private fun injectNavigateFunction(cefBrowser: CefBrowser) {
        val js = """
            window.navigateToFile = function(path) {
                ${navigateQuery.inject("path")}
            };
        """.trimIndent()
        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
    }

    private fun injectToggleFunction(cefBrowser: CefBrowser) {
        val js = """
            window.markComplete = function(order, isCompleted) {
                ${toggleQuery.inject("order + ':' + isCompleted")}
            };
        """.trimIndent()
        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
    }

    private fun injectData(cefBrowser: CefBrowser, structure: ProjectStructure) {
        val jsonData = gson.toJson(structure.startHerePath)
        val service = com.jetslop.illuminate.services.IlluminateService.getInstance(project)
        val completedJson = gson.toJson(service.completedStartHereItems)
        cefBrowser.executeJavaScript("renderStartHere($jsonData, $completedJson);", cefBrowser.url, 0)
    }
}
