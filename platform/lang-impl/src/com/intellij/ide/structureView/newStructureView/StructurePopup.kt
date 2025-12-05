// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.structureView.newStructureView

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
interface StructurePopup: TreeActionsOwner {
  fun show()
  fun setTitle(title: @NlsContexts.PopupTitle String)
}

@ApiStatus.Internal
interface StructurePopupProvider {
  fun createPopup(project: Project, fileEditor: FileEditor): StructurePopup?

  companion object {
    fun createPopup(project: Project, fileEditor: FileEditor): StructurePopup? {
      return EP.extensionList.firstNotNullOfOrNull { it.createPopup(project, fileEditor) }
    }

    val EP: ExtensionPointName<StructurePopupProvider> = ExtensionPointName.create("com.intellij.structurePopupProvider")
  }
}