// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.backend

import com.intellij.ide.structureView.newStructureView.TreeActionsOwner
import com.intellij.ide.structureView.newStructureView.TreeActionsOwnerEx
import com.intellij.ide.util.treeView.smartTree.Filter
import com.intellij.ide.util.treeView.smartTree.NodeProvider
import com.intellij.ide.util.treeView.smartTree.TreeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

internal class BackendTreeActionOwner : TreeActionsOwner, TreeActionsOwnerEx {
  override fun setActionActive(name: String?, state: Boolean) {}

  override fun isActionActive(name: String?): Boolean = false

  override fun setActionActive(action: TreeAction, state: Boolean) {}

  override fun isActionActive(action: TreeAction): Boolean {
    // always false for filters so that the elements are not filtered out
    if (action is Filter) return false
    // always true for node providers so that all possible elements are calculated
    if (action is NodeProvider<*>) return true

    return BackendTreeActionOwnerService.getInstance().isActionActive(action.name)
  }
}

@Service
internal class BackendTreeActionOwnerService {
  private val activeActions = hashSetOf<String>()

  fun setActionActive(name: String, state: Boolean) {
    if (state) {
      activeActions.add(name)
    } else {
      activeActions.remove(name)
    }
  }

  fun isActionActive(name: String): Boolean = name in activeActions

  companion object {
    @JvmStatic
    fun getInstance(): BackendTreeActionOwnerService = service()
  }
}