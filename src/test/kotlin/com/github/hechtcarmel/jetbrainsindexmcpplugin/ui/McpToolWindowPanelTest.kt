package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandFilter
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.buildJsonObject
import javax.swing.DefaultListModel
import javax.swing.JButton

class McpToolWindowPanelTest : BasePlatformTestCase() {

    private lateinit var historyService: CommandHistoryService
    private var originalMaxHistorySize: Int = 100

    override fun setUp() {
        super.setUp()
        originalMaxHistorySize = McpSettings.getInstance().maxHistorySize
        historyService = CommandHistoryService.getInstance(project)
        historyService.clearHistory()
    }

    override fun tearDown() {
        try {
            historyService.clearHistory()
            McpSettings.getInstance().maxHistorySize = originalMaxHistorySize
        } finally {
            super.tearDown()
        }
    }

    fun testFilteredHistoryRefreshRemovesTrimmedEntries() {
        McpSettings.getInstance().maxHistorySize = 1

        val panel = McpToolWindowPanel(project)
        try {
            applyFilter(panel, CommandFilter(toolName = "match"))

            historyService.recordCommand(CommandEntry(
                toolName = "match",
                parameters = buildJsonObject { }
            ))
            dispatchUiEvents()
            assertEquals(listOf("match"), visibleToolNames(panel))

            historyService.recordCommand(CommandEntry(
                toolName = "other",
                parameters = buildJsonObject { }
            ))
            dispatchUiEvents()

            assertTrue(
                "Filtered history should be rebuilt from the bounded service snapshot",
                visibleToolNames(panel).isEmpty()
            )
        } finally {
            panel.dispose()
        }
    }

    fun testToolWindowContainsEndpointInventoryPanel() {
        val panel = McpToolWindowPanel(project)
        try {
            val endpointPanel = findComponent(panel, EndpointInventoryPanel::class.java)

            assertNotNull(
                "Endpoint inventory panel should be part of the tool window header",
                endpointPanel
            )
        } finally {
            panel.dispose()
        }
    }

    fun testEndpointInventoryPanelDefaultsToCollapsedSummary() {
        val endpointPanel = EndpointInventoryPanel()
        endpointPanel.setRows(sampleEndpointRows())

        val toggle = findComponent(endpointPanel, JButton::class.java)

        assertNotNull("Endpoint inventory should expose a compact toggle", toggle)
        assertFalse("Endpoint rows should be collapsed by default", endpointPanel.isExpandedForTest())
        assertEquals("> Endpoints: 1 workspace, 2 repos", toggle!!.text)
    }

    fun testEndpointInventoryPanelTogglesExpandedRows() {
        val endpointPanel = EndpointInventoryPanel()
        endpointPanel.setRows(sampleEndpointRows())
        val toggle = findComponent(endpointPanel, JButton::class.java)!!

        toggle.doClick()
        dispatchUiEvents()

        assertTrue("Endpoint rows should expand after toggle click", endpointPanel.isExpandedForTest())
        assertEquals("v Endpoints: 1 workspace, 2 repos", toggle.text)

        toggle.doClick()
        dispatchUiEvents()

        assertFalse("Endpoint rows should collapse after second toggle click", endpointPanel.isExpandedForTest())
        assertEquals("> Endpoints: 1 workspace, 2 repos", toggle.text)
    }

    private fun applyFilter(panel: McpToolWindowPanel, filter: CommandFilter) {
        val filterField = McpToolWindowPanel::class.java.getDeclaredField("currentFilter")
        filterField.isAccessible = true
        filterField.set(panel, filter)

        val refreshMethod = McpToolWindowPanel::class.java.getDeclaredMethod("refreshHistory")
        refreshMethod.isAccessible = true
        refreshMethod.invoke(panel)
    }

    @Suppress("UNCHECKED_CAST")
    private fun visibleToolNames(panel: McpToolWindowPanel): List<String> {
        val modelField = McpToolWindowPanel::class.java.getDeclaredField("historyListModel")
        modelField.isAccessible = true
        val model = modelField.get(panel) as DefaultListModel<CommandEntry>
        return (0 until model.size()).map { model.getElementAt(it).toolName }
    }

    private fun dispatchUiEvents() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun sampleEndpointRows(): List<EndpointInventoryRow> = listOf(
        EndpointInventoryRow(
            id = "workspace",
            scopeKind = EndpointScopeKind.WORKSPACE,
            scopeName = "Workspace",
            url = "http://127.0.0.1:29170/index-mcp/streamable-http",
            rootPath = "C:/Workspace",
            state = EndpointInventoryState.RUNNING
        ),
        EndpointInventoryRow(
            id = "repo:ePC-SAFT",
            scopeKind = EndpointScopeKind.REPO,
            scopeName = "ePC-SAFT",
            url = "http://127.0.0.1:29170/index-mcp/repos/ePC-SAFT/streamable-http",
            rootPath = "C:/Workspace/ePC-SAFT",
            state = EndpointInventoryState.RUNNING
        ),
        EndpointInventoryRow(
            id = "repo:jetbrains-bridge",
            scopeKind = EndpointScopeKind.REPO,
            scopeName = "jetbrains-bridge",
            url = "http://127.0.0.1:29170/index-mcp/repos/jetbrains-bridge/streamable-http",
            rootPath = "C:/Workspace/jetbrains-bridge",
            state = EndpointInventoryState.RUNNING
        )
    )

    private fun <T : java.awt.Component> findComponent(root: java.awt.Container, type: Class<T>): T? {
        for (component in root.components) {
            if (type.isInstance(component)) {
                return type.cast(component)
            }
            if (component is java.awt.Container) {
                val nested = findComponent(component, type)
                if (nested != null) {
                    return nested
                }
            }
        }
        return null
    }
}
