package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase
import kotlinx.serialization.json.jsonObject

class GetRepoScopedClientConfigToolUnitTest : TestCase() {

    fun testSchemaIncludesProjectPathOnly() {
        val tool = GetRepoScopedClientConfigTool()
        val properties = tool.inputSchema[SchemaConstants.PROPERTIES]?.jsonObject

        assertEquals(ToolNames.GET_REPO_SCOPED_CLIENT_CONFIG, tool.name)
        assertNotNull(properties?.get(ParamNames.PROJECT_PATH))
        assertNull(tool.inputSchema[SchemaConstants.REQUIRED])
    }
}
