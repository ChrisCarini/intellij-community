// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.impl

import com.intellij.ide.rpc.FileEditorId
import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import com.intellij.platform.structureView.impl.dto.StructureViewModelDto
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.TestOnly

@Internal
@Rpc
interface StructureTreeApi : RemoteApi<Unit> {
  suspend fun getStructureViewModel(fileEditorId: FileEditorId, fileId: VirtualFileId, projectId: ProjectId, id: Int): StructureViewModelDto?

  suspend fun structureViewModelDisposed(id: Int)

  suspend fun setTreeActionState(id: Int, actionName: String, isEnabled: Boolean, autoClicked: Boolean)

  @TestOnly
  suspend fun getNewSelection(id: Int): Int?

  suspend fun navigateToElement(id: Int, elementId: Int): Boolean

  companion object {
    suspend fun getInstance(): StructureTreeApi {
      return RemoteApiProviderService.resolve(remoteApiDescriptor<StructureTreeApi>())
    }
  }
}