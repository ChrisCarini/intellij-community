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

internal class DisallowListBasedMcpToolFilterProvider(val cs: CoroutineScope) : McpToolFilterProvider {
  companion object {
    internal const val ENABLE_GIT_STATUS_TOOL_REGISTRY_KEY: String = "mcp.server.tools.enable.git.status"
    internal const val ENABLE_APPLY_PATCH_TOOL_REGISTRY_KEY: String = "mcp.server.tools.enable.apply.patch"
    private const val GIT_STATUS_TOOL_NAME: String = "git_status"
    private const val APPLY_PATCH_TOOL_NAME: String = "apply_patch"
  }

  override fun getFilters(clientInfo: Implementation?): StateFlow<List<McpToolFilter>> {
    val settings = McpToolDisallowListSettings.getInstance()
    return combine(
      settings.disallowedToolNamesFlow,
      Registry.get(ENABLE_GIT_STATUS_TOOL_REGISTRY_KEY).asBooleanFlow(),
      Registry.get(ENABLE_APPLY_PATCH_TOOL_REGISTRY_KEY).asBooleanFlow(),
    ) { disallowedNames, isGitStatusEnabled, isApplyPatchEnabled ->
      listOf(
        DisallowListMcpToolFilter(
          withRegistryDisabledTools(
            disallowedNames = disallowedNames,
            isGitStatusEnabled = isGitStatusEnabled,
            isApplyPatchEnabled = isApplyPatchEnabled,
          )
        )
      )
    }.stateIn(
      cs,
      SharingStarted.Lazily,
      listOf(
        DisallowListMcpToolFilter(
          withRegistryDisabledTools(
            disallowedNames = settings.disallowedToolNames,
            isGitStatusEnabled = Registry.`is`(ENABLE_GIT_STATUS_TOOL_REGISTRY_KEY),
            isApplyPatchEnabled = Registry.`is`(ENABLE_APPLY_PATCH_TOOL_REGISTRY_KEY),
          )
        )
      )
    )
  }

  private fun withRegistryDisabledTools(
    disallowedNames: Set<String>,
    isGitStatusEnabled: Boolean,
    isApplyPatchEnabled: Boolean,
  ): Set<String> {
    if (isGitStatusEnabled && isApplyPatchEnabled) return disallowedNames

    return buildSet {
      addAll(disallowedNames)
      if (!isGitStatusEnabled) add(GIT_STATUS_TOOL_NAME)
      if (!isApplyPatchEnabled) add(APPLY_PATCH_TOOL_NAME)
    }
  }

  private class DisallowListMcpToolFilter(private val disallowedNames: Set<String>) : McpToolFilter {
    override fun modify(context: McpToolFilterContext): McpToolFilterModification {
      val toolsToDisallow = context.allowedTools.filter { it.descriptor.name in disallowedNames }.toSet()
      return DisallowMcpTools(toolsToDisallow)
    }
  }
}
