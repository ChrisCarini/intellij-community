package com.intellij.mcpserver.impl

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolFilterProvider.DisallowMcpTools
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilter
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilterContext
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilterModification
import com.intellij.mcpserver.settings.McpToolDisallowListSettings
import com.intellij.openapi.util.registry.Registry
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class DisallowListBasedMcpToolFilterProvider : McpToolFilterProvider {
  companion object {
    internal const val ENABLE_GIT_STATUS_TOOL_REGISTRY_KEY: String = "mcp.server.tools.enable.git.status"
    internal const val ENABLE_APPLY_PATCH_TOOL_REGISTRY_KEY: String = "mcp.server.tools.enable.apply.patch"
    private const val GIT_STATUS_TOOL_NAME: String = "git_status"
    private const val APPLY_PATCH_TOOL_NAME: String = "apply_patch"
  }

  override fun getFilters(clientInfo: Implementation?): List<McpToolFilter> {
    val settings = McpToolDisallowListSettings.getInstance()
    return listOf(DisallowListMcpToolFilter(settings.disallowedToolNames))
  }

  override fun getUpdates(clientInfo: Implementation?, scope: CoroutineScope): Flow<Unit> {
    val settings = McpToolDisallowListSettings.getInstance()
    return settings.disallowedToolNamesFlow.map { }
  }

  private class DisallowListMcpToolFilter(private val disallowedNames: Set<String>) : McpToolFilter {
    override fun modify(context: McpToolFilterContext): McpToolFilterModification {
      val toolsToDisallow = context.allowedTools.filter { it.descriptor.name in disallowedNames }.toSet()
      return DisallowMcpTools(toolsToDisallow)
    }
  }
}
