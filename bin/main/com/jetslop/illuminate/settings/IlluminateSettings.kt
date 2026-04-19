package com.jetslop.illuminate.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.jetslop.illuminate.ai.AIProvider

@State(
    name = "IlluminateSettings",
    storages = [Storage("illuminate.xml")]
)
class IlluminateSettings : PersistentStateComponent<IlluminateSettings.State> {

    data class State(
        var apiKey: String = "",
        var model: String = "gpt-4o",
        var providerName: String = "OPENAI",
        var autoScanOnOpen: Boolean = false,
        var enableAiHud: Boolean = true,
        var showLabelsDefault: Boolean = false,
        var showModulesDefault: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var apiKey: String
        get() = myState.apiKey
        set(value) { myState.apiKey = value }

    var model: String
        get() = myState.model
        set(value) { myState.model = value }

    var provider: AIProvider
        get() = try { AIProvider.valueOf(myState.providerName) } catch (_: Exception) { AIProvider.ANTHROPIC }
        set(value) { myState.providerName = value.name }

    var autoScanOnOpen: Boolean
        get() = myState.autoScanOnOpen
        set(value) { myState.autoScanOnOpen = value }

    var enableAiHud: Boolean
        get() = myState.enableAiHud
        set(value) { myState.enableAiHud = value }

    companion object {
        fun getInstance(): IlluminateSettings {
            return ApplicationManager.getApplication().getService(IlluminateSettings::class.java)
        }
    }
}
