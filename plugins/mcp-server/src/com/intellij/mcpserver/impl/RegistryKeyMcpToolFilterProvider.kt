package com.intellij.mcpserver.impl

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolFilterProvider.MaskBasedMcpToolFilter.Companion.getMaskFilters
import com.intellij.openapi.util.registry.Registry
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RegistryKeyMcpToolFilterProvider : McpToolFilterProvider {
  override fun getFilters(clientInfo: Implementation?): List<McpToolFilterProvider.McpToolFilter> {
    return getMaskFilters(Registry.stringValue("mcp.server.tools.filter"))
  }

  override fun getUpdates(clientInfo: Implementation?, scope: CoroutineScope): Flow<Unit> {
    return Registry.get("mcp.server.tools.filter").asStringFlow().map { }
  }
}
