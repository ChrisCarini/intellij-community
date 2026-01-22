// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.frontend

import com.intellij.ide.structureView.newStructureView.StructurePopup
import com.intellij.ide.structureView.newStructureView.StructurePopupProvider
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.structureView.frontend.uiModel.StructureUiModelImpl
import java.util.function.Consumer

class StructurePopupProviderImpl: StructurePopupProvider {
  override fun createPopup(project: Project, fileEditor: FileEditor): StructurePopup? {
    if (!Registry.`is`("frontend.structure.popup")) return null
    val file = fileEditor.file
    return FileStructurePopup(project, fileEditor, StructureUiModelImpl(fileEditor, file, project))
  }

  @Deprecated("Use createPopup instead", replaceWith = ReplaceWith("createPopup(project, fileEditor)"))
  override fun createPopup(
    project: Project,
    fileEditor: FileEditor,
    callbackAfterNavigation: Consumer<AbstractTreeNode<*>>?
  ): StructurePopup? {
    return createPopup(project, fileEditor)
  }
}