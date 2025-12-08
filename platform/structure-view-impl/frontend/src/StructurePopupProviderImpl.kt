// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.frontend

import com.intellij.ide.structureView.newStructureView.StructurePopup
import com.intellij.ide.structureView.newStructureView.StructurePopupProvider
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.structureView.impl.uiModel.StructureUiModelImpl

class StructurePopupProviderImpl: StructurePopupProvider {
  override fun createPopup(project: Project, fileEditor: FileEditor): StructurePopup? {
    if (!Registry.`is`("frontend.structure.popup")) return null
    val file = fileEditor.file
    val editor = (fileEditor as? TextEditor)?.editor
    return FileStructurePopup(project, fileEditor, StructureUiModelImpl(file, project, editor))
  }
}