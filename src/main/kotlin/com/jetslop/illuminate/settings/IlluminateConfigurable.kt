package com.jetslop.illuminate.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.jetslop.illuminate.ai.AIProvider
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class IlluminateConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private val providerCombo = JComboBox(arrayOf("Anthropic (Claude)", "OpenAI"))
    private val apiKeyField = JBPasswordField()
    private val modelField = JBTextField()
    private val autoScanCheckBox = JBCheckBox("Auto-scan project when tool window opens")
    private val aiHudCheckBox = JBCheckBox("Enable AI-powered deep explanations in Code HUD")

    override fun getDisplayName(): String = "Illuminate"

    override fun createComponent(): JComponent {
        val settings = IlluminateSettings.getInstance()

        mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
            anchor = GridBagConstraints.WEST
        }

        // AI Configuration header
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        mainPanel!!.add(JLabel("<html><b>AI Configuration</b></html>"), gbc)

        gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.0
        mainPanel!!.add(JLabel("AI Provider:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        providerCombo.addActionListener {
            updateModelHint()
        }
        mainPanel!!.add(providerCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        mainPanel!!.add(JLabel("API Key:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        apiKeyField.columns = 30
        mainPanel!!.add(apiKeyField, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        mainPanel!!.add(JLabel("Model:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        modelField.columns = 25
        mainPanel!!.add(modelField, gbc)

        // Behavior header
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 0.0
        gbc.insets = Insets(16, 8, 4, 8)
        mainPanel!!.add(JLabel("<html><b>Behavior</b></html>"), gbc)

        gbc.gridy = 5; gbc.insets = Insets(4, 8, 4, 8)
        mainPanel!!.add(autoScanCheckBox, gbc)

        gbc.gridy = 6
        mainPanel!!.add(aiHudCheckBox, gbc)

        // About
        gbc.gridy = 7; gbc.insets = Insets(16, 8, 4, 8)
        mainPanel!!.add(JLabel("<html><b>Illuminate</b> — Instantly understand any codebase.<br>" +
                "Press Ctrl+Shift+I or go to Tools → Illuminate Project to scan.<br>" +
                "Claude is recommended for best code analysis quality.<br>" +
                "Version 1.0.0 · JetSlop Team · Hackathon Košice 2026</html>"), gbc)

        // Spacer
        gbc.gridy = 8; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        mainPanel!!.add(JPanel(), gbc)

        // Load current values
        providerCombo.selectedIndex = if (settings.provider == AIProvider.ANTHROPIC) 0 else 1
        apiKeyField.text = settings.apiKey
        modelField.text = settings.model
        autoScanCheckBox.isSelected = settings.autoScanOnOpen
        aiHudCheckBox.isSelected = settings.enableAiHud

        return mainPanel!!
    }

    private fun updateModelHint() {
        val currentModel = modelField.text
        if (providerCombo.selectedIndex == 0) {
            if (currentModel.startsWith("gpt-")) {
                modelField.text = "claude-sonnet-4-20250514"
            }
        } else {
            if (currentModel.startsWith("claude-")) {
                modelField.text = "gpt-4o-mini"
            }
        }
    }

    private fun selectedProvider(): AIProvider =
        if (providerCombo.selectedIndex == 0) AIProvider.ANTHROPIC else AIProvider.OPENAI

    override fun isModified(): Boolean {
        val settings = IlluminateSettings.getInstance()
        return String(apiKeyField.password) != settings.apiKey ||
                modelField.text != settings.model ||
                selectedProvider() != settings.provider ||
                autoScanCheckBox.isSelected != settings.autoScanOnOpen ||
                aiHudCheckBox.isSelected != settings.enableAiHud
    }

    override fun apply() {
        val settings = IlluminateSettings.getInstance()
        settings.apiKey = String(apiKeyField.password)
        settings.model = modelField.text
        settings.provider = selectedProvider()
        settings.autoScanOnOpen = autoScanCheckBox.isSelected
        settings.enableAiHud = aiHudCheckBox.isSelected
    }

    override fun reset() {
        val settings = IlluminateSettings.getInstance()
        providerCombo.selectedIndex = if (settings.provider == AIProvider.ANTHROPIC) 0 else 1
        apiKeyField.text = settings.apiKey
        modelField.text = settings.model
        autoScanCheckBox.isSelected = settings.autoScanOnOpen
        aiHudCheckBox.isSelected = settings.enableAiHud
    }
}
