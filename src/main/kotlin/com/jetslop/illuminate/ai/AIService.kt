package com.jetslop.illuminate.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jetslop.illuminate.model.FileNode
import com.jetslop.illuminate.model.ProjectStructure
import com.jetslop.illuminate.settings.IlluminateSettings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class AIProvider { OPENAI, ANTHROPIC }

class AIService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // Cache AI explanations to avoid repeated calls
    private val hudCache = ConcurrentHashMap<String, String>()

    fun clearCache() = hudCache.clear()

    /**
     * Generate a deep AI explanation for a class — used in Code HUD.
     * Returns cached result or calls LLM.
     */
    fun explainClassForHud(node: FileNode, structure: ProjectStructure): String? {
        val settings = IlluminateSettings.getInstance()
        if (settings.apiKey.isBlank()) return null

        val cacheKey = "class:${node.id}"
        hudCache[cacheKey]?.let { return it }

        val deps = structure.edges.filter { it.source == node.id }
            .map { "${it.target.substringAfterLast('.')} (${it.type.name.lowercase()})" }
        val usedBy = structure.edges.filter { it.target == node.id }
            .map { it.source.substringAfterLast('.') }

        val architectureSnippet = buildCompactContext(structure)

        val prompt = """You are analyzing a Java class within a project. Give a SHORT (max 15 words) but DEEP explanation of what this class does and WHY it exists in the architecture. Think about the business purpose, not just technical details.

PROJECT CONTEXT:
$architectureSnippet

CLASS: ${node.qualifiedName}
Type: ${node.type.label}
Module: ${node.module}
Methods: ${node.methods.joinToString(", ")}
Annotations: ${node.annotations.joinToString(", ")}
Depends on: ${deps.joinToString(", ").ifEmpty { "nothing" }}
Used by: ${usedBy.joinToString(", ").ifEmpty { "nothing" }}

Reply with ONLY the explanation, no quotes, no prefix. Max 15 words."""

        val result = callLLM(prompt) ?: return null
        hudCache[cacheKey] = result
        return result
    }

    /**
     * Generate a deep AI explanation for a method — used in Code HUD.
     */
    fun explainMethodForHud(
        className: String,
        methodName: String,
        methodSignature: String,
        methodBody: String,
        node: FileNode,
        structure: ProjectStructure
    ): String? {
        val settings = IlluminateSettings.getInstance()
        if (settings.apiKey.isBlank()) return null

        val cacheKey = "method:$className.$methodName"
        hudCache[cacheKey]?.let { return it }

        val architectureSnippet = buildCompactContext(structure)

        // Limit method body to avoid huge prompts
        val bodySnippet = if (methodBody.length > 800) methodBody.take(800) + "..." else methodBody

        val prompt = """You are analyzing a Java method. Give a SHORT (max 12 words) but INSIGHTFUL explanation of what this method does, its business purpose, and any important side effects.

PROJECT CONTEXT:
$architectureSnippet

CLASS: ${node.qualifiedName} (${node.type.label})
METHOD: $methodSignature

CODE:
$bodySnippet

Reply with ONLY the explanation, no quotes, no prefix. Max 12 words. Focus on WHAT and WHY, not HOW."""

        val result = callLLM(prompt) ?: return null
        hudCache[cacheKey] = result
        return result
    }

    fun summarizeModule(moduleName: String, classes: List<FileNode>): String {
        val settings = IlluminateSettings.getInstance()
        if (settings.apiKey.isBlank()) return "Configure API key in Settings → Tools → Illuminate"

        val classInfo = classes.joinToString("\n") { node ->
            "- ${node.name} (${node.type.label}): ${node.methods.take(5).joinToString(", ")}"
        }

        val prompt = """Describe this code module in 2-3 sentences. Be concise and practical.
Module: $moduleName
Classes:
$classInfo

Focus on: what this module does, its role in the system, and key patterns used."""

        return callLLM(prompt) ?: "Error generating summary"
    }

    fun summarizeClass(node: FileNode, structure: ProjectStructure): String {
        val settings = IlluminateSettings.getInstance()
        if (settings.apiKey.isBlank()) return "Configure API key in Settings → Tools → Illuminate"

        val deps = structure.edges.filter { it.source == node.id }
            .map { edge -> edge.target.substringAfterLast('.') + " (${edge.type.name.lowercase()})" }
        val usedBy = structure.edges.filter { it.target == node.id }
            .map { edge -> edge.source.substringAfterLast('.') }

        val prompt = """Describe this class in 1-2 sentences for a developer who just cloned the project.
Class: ${node.qualifiedName} (${node.type.label})
Methods: ${node.methods.joinToString(", ")}
Annotations: ${node.annotations.joinToString(", ")}
Depends on: ${deps.joinToString(", ").ifEmpty { "nothing" }}
Used by: ${usedBy.joinToString(", ").ifEmpty { "nothing" }}

Be practical — what does it do and why does it matter?"""

        return callLLM(prompt) ?: "Error generating summary"
    }

    fun chatAboutProject(question: String, structure: ProjectStructure): String {
        val settings = IlluminateSettings.getInstance()
        if (settings.apiKey.isBlank()) return "Configure API key in Settings → Tools → Illuminate"

        val architectureContext = buildArchitectureContext(structure)
        val prompt = """You are an expert code assistant helping a developer understand a codebase they just cloned.

PROJECT ARCHITECTURE:
$architectureContext

DEVELOPER'S QUESTION: $question

Answer concisely and practically. Reference specific classes/modules when relevant. Use markdown formatting."""

        return callLLM(prompt) ?: "Error generating response"
    }

    /**
     * Deep explanation of a class — used when user clicks on a HUD inlay.
     * Returns a detailed markdown explanation.
     */
    fun deepExplainClass(node: FileNode, structure: ProjectStructure): String {
        val settings = IlluminateSettings.getInstance()
        if (settings.apiKey.isBlank()) return "Configure API key in Settings → Tools → Illuminate"

        val architectureContext = buildArchitectureContext(structure)
        val deps = structure.edges.filter { it.source == node.id }
            .map { "${it.target.substringAfterLast('.')} (${it.type.name.lowercase()})" }
        val usedBy = structure.edges.filter { it.target == node.id }
            .map { it.source.substringAfterLast('.') }

        val prompt = """You are a senior developer explaining a class to a newcomer who just cloned this project. Give a DETAILED explanation that helps them fully understand this class.

PROJECT ARCHITECTURE:
$architectureContext

CLASS: ${node.qualifiedName}
Type: ${node.type.label}
Module: ${node.module}
Methods: ${node.methods.joinToString(", ")}
Annotations: ${node.annotations.joinToString(", ")}
Depends on: ${deps.joinToString(", ").ifEmpty { "nothing" }}
Used by: ${usedBy.joinToString(", ").ifEmpty { "nothing" }}

Write a clear explanation covering:
1. **Purpose** — What this class does and WHY it exists (business reason)
2. **How it works** — Key methods and their roles
3. **Data flow** — How data enters, transforms, and exits this class
4. **Dependencies** — Why it depends on what it depends on, and what depends on it and why
5. **Key patterns** — Any design patterns, annotations, or architectural patterns used

Use markdown. Be practical and specific, not generic. 150-250 words max."""

        return callLLM(prompt, maxTokens = 600) ?: "Error generating explanation"
    }

    /**
     * Deep explanation of a method — used when user clicks on a method HUD inlay.
     */
    fun deepExplainMethod(
        className: String,
        methodName: String,
        methodSignature: String,
        methodBody: String,
        node: FileNode,
        structure: ProjectStructure
    ): String {
        val settings = IlluminateSettings.getInstance()
        if (settings.apiKey.isBlank()) return "Configure API key in Settings → Tools → Illuminate"

        val architectureContext = buildArchitectureContext(structure)
        val bodySnippet = if (methodBody.length > 1500) methodBody.take(1500) + "\n// ... truncated" else methodBody

        val prompt = """You are a senior developer explaining a method to a newcomer. Give a DETAILED explanation.

PROJECT ARCHITECTURE:
$architectureContext

CLASS: ${node.qualifiedName} (${node.type.label})
METHOD: $methodSignature

FULL CODE:
$bodySnippet

Write a clear explanation covering:
1. **What it does** — Purpose and business logic in plain language
2. **Step by step** — Walk through the code logic
3. **Inputs & Outputs** — What comes in, what goes out, side effects
4. **Error handling** — How errors are handled, edge cases
5. **Connections** — How this method relates to other parts of the system

Use markdown. Be specific about THIS code, not generic. 150-250 words max."""

        return callLLM(prompt, maxTokens = 600) ?: "Error generating explanation"
    }

    private fun buildCompactContext(structure: ProjectStructure): String {
        val sb = StringBuilder()
        sb.appendLine("Modules: ${structure.modules.joinToString(", ") { "${it.name}(${it.fileCount})" }}")
        sb.appendLine("Key classes: ${structure.nodes.sortedByDescending { it.importance }.take(15).joinToString(", ") { "${it.name}[${it.type.label}]" }}")
        sb.appendLine("${structure.stats.totalClasses} classes, ${structure.stats.totalModules} modules, ${structure.stats.totalLines} LOC")
        return sb.toString()
    }

    private fun buildArchitectureContext(structure: ProjectStructure): String {
        val sb = StringBuilder()

        sb.appendLine("== Modules ==")
        for (module in structure.modules) {
            sb.appendLine("${module.name} (${module.fileCount} files)")
        }

        sb.appendLine("\n== Key Classes ==")
        for (node in structure.nodes.sortedByDescending { it.importance }.take(30)) {
            sb.appendLine("${node.qualifiedName} [${node.type.label}] — methods: ${node.methods.take(5).joinToString(", ")}")
        }

        sb.appendLine("\n== Dependencies ==")
        for (edge in structure.edges.take(50)) {
            sb.appendLine("${edge.source.substringAfterLast('.')} → ${edge.target.substringAfterLast('.')} (${edge.type.name.lowercase()})")
        }

        sb.appendLine("\n== Stats ==")
        sb.appendLine("${structure.stats.totalClasses} classes, ${structure.stats.totalModules} modules, ${structure.stats.totalDependencies} dependencies, ${structure.stats.totalLines} LOC")

        return sb.toString()
    }

    private fun callLLM(prompt: String, maxTokens: Int = 300): String? {
        val settings = IlluminateSettings.getInstance()
        val provider = settings.provider
        val apiKey = settings.apiKey
        val model = settings.model

        return when (provider) {
            AIProvider.ANTHROPIC -> callClaude(prompt, apiKey, model, maxTokens)
            AIProvider.OPENAI -> callOpenAI(prompt, apiKey, model, maxTokens)
        }
    }

    private fun callClaude(prompt: String, apiKey: String, model: String, maxTokens: Int = 300): String? {
        val body = gson.toJson(
            mapOf(
                "model" to model,
                "max_tokens" to maxTokens,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                )
            )
        )

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "API error: ${response.code} — ${response.message}"
                }
                val json = JsonParser.parseString(response.body?.string() ?: "")
                json.asJsonObject
                    .getAsJsonArray("content")[0].asJsonObject
                    .get("text").asString.trim()
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun callOpenAI(prompt: String, apiKey: String, model: String, maxTokens: Int = 300): String? {
        val body = gson.toJson(
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                ),
                "max_tokens" to maxTokens,
                "temperature" to 0.3
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "API error: ${response.code} — ${response.message}"
                }
                val json = JsonParser.parseString(response.body?.string() ?: "")
                json.asJsonObject
                    .getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message")
                    .get("content").asString.trim()
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private var instance: AIService? = null
        fun getInstance(): AIService {
            if (instance == null) instance = AIService()
            return instance!!
        }
    }
}
