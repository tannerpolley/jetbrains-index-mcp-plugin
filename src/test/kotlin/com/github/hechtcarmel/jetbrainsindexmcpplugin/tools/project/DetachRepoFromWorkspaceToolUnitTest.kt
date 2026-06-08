package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase
import kotlinx.serialization.json.jsonObject

class DetachRepoFromWorkspaceToolUnitTest : TestCase() {

    fun testSchemaRequiresRepoIdAndIncludesProjectPath() {
        val tool = DetachRepoFromWorkspaceTool()
        val properties = tool.inputSchema[SchemaConstants.PROPERTIES]?.jsonObject

        assertEquals(ToolNames.DETACH_REPO_FROM_WORKSPACE, tool.name)
        assertNotNull(properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull(properties?.get(ParamNames.REPO_ID))
        assertTrue(tool.inputSchema[SchemaConstants.REQUIRED].toString().contains(ParamNames.REPO_ID))
    }
}
