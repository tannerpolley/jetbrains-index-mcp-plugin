package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase
import kotlinx.serialization.json.jsonObject

class AttachRepoToWorkspaceToolUnitTest : TestCase() {

    fun testSchemaRequiresRepoPathAndIncludesProjectPath() {
        val tool = AttachRepoToWorkspaceTool()
        val properties = tool.inputSchema[SchemaConstants.PROPERTIES]?.jsonObject

        assertEquals(ToolNames.ATTACH_REPO_TO_WORKSPACE, tool.name)
        assertNotNull(properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull(properties?.get(ParamNames.REPO_PATH))
        assertTrue(tool.inputSchema[SchemaConstants.REQUIRED].toString().contains(ParamNames.REPO_PATH))
    }
}
