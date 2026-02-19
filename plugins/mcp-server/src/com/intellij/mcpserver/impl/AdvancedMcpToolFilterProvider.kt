package com.intellij.mcpserver.impl

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolFilterProvider.MaskBasedMcpToolFilter.Companion.getMaskFilters
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Filter provider that uses advancedMcpToolFilter from McpSessionOptions.
 * This allows session-specific tool filtering based on filter strings passed during session creation.
 */
internal class AdvancedMcpToolFilterProvider : McpToolFilterProvider {
  override fun getFilters(clientInfo: Implementation?, sessionOptions: McpServerService.McpSessionOptions?): List<McpToolFilterProvider.McpToolFilter> {
    val filterString = sessionOptions?.advancedMcpToolFilter ?: return emptyList()
    return getMaskFilters(filterString)
  }

  override fun getUpdates(clientInfo: Implementation?, scope: CoroutineScope, sessionOptions: McpServerService.McpSessionOptions?): Flow<Unit> {
    // Filter never changes during session - it's set at session creation time
    return emptyFlow()
  }
}
