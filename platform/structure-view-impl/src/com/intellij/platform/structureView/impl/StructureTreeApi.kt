// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.impl

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.openapi.editor.impl.EditorId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import com.intellij.platform.structureView.impl.dto.StructureViewModelDto
import com.intellij.platform.structureView.impl.dto.StructureViewModelDtoId
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import org.jetbrains.annotations.ApiStatus.Internal

@Internal
@Rpc
interface StructureTreeApi : RemoteApi<Unit> {
  suspend fun getStructureViewModelFlow(editorId: EditorId?, fileId: VirtualFileId, projectId: ProjectId, id: StructureViewModelDtoId): Flow<StructureViewModelDto?>

  suspend fun structureViewModelDisposed(id: StructureViewModelDtoId)

  suspend fun setTreeActionState(id: StructureViewModelDtoId, actionName: String, isEnabled: Boolean)

  companion object {
    suspend fun getInstance(): StructureTreeApi {
      return RemoteApiProviderService.resolve(remoteApiDescriptor<StructureTreeApi>())
    }
  }
}