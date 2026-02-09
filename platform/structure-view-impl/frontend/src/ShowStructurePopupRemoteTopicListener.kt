// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.frontend

import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.rpc.topics.ProjectRemoteTopic
import com.intellij.platform.rpc.topics.ProjectRemoteTopicListener
import com.intellij.platform.structureView.frontend.uiModel.StructureUiModelImpl
import com.intellij.platform.structureView.impl.SHOW_STRUCTURE_POPUP_REMOTE_TOPIC
import com.intellij.platform.structureView.impl.ShowStructurePopupRequest
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
internal class ShowStructurePopupRemoteTopicListener : ProjectRemoteTopicListener<ShowStructurePopupRequest> {
  override val topic: ProjectRemoteTopic<ShowStructurePopupRequest> = SHOW_STRUCTURE_POPUP_REMOTE_TOPIC

  override fun handleEvent(project: Project, event: ShowStructurePopupRequest) {
    runInEdt {
      if (!Registry.`is`("frontend.structure.popup")) return@runInEdt

      val file = event.fileId.virtualFile() ?: return@runInEdt
      val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(file) ?: return@runInEdt

      val model = StructureUiModelImpl(event.model, file.name)
      val popup = FileStructurePopup(project, fileEditor, model)
      event.title?.let { popup.setTitle(it) }
      popup.show()
    }
  }
}
