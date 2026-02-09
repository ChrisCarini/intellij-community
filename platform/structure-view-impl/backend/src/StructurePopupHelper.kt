// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.backend

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.vfs.rpcId
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.rpc.topics.sendToClient
import com.intellij.platform.structureView.impl.SHOW_STRUCTURE_POPUP_REMOTE_TOPIC
import com.intellij.platform.structureView.impl.ShowStructurePopupRequest
import com.intellij.platform.structureView.impl.StructureViewScopeHolder
import com.intellij.util.application
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
fun showFileStructurePopup(
  project: Project,
  fileEditor: FileEditor,
  virtualFile: VirtualFile,
  callback: (AbstractTreeNode<*>) -> Unit
) {
  StructureViewScopeHolder.getInstance().cs.launch {
    val model = application.service<BackendStructureTreeService>().getStructureViewModel(
      project,
      fileEditor,
      callback,
    ) ?: return@launch

    val request = ShowStructurePopupRequest(
      fileId = virtualFile.rpcId(),
      title = virtualFile.name,
      model = model
    )
    SHOW_STRUCTURE_POPUP_REMOTE_TOPIC.sendToClient(project, request)
  }
}
