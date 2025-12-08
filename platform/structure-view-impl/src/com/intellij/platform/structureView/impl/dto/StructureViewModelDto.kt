// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.impl.dto

import com.intellij.platform.structureView.impl.uiModel.NodeProviderTreeAction
import com.intellij.platform.structureView.impl.uiModel.StructureTreeAction
import fleet.rpc.core.RpcFlow
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
@Serializable
data class StructureViewModelDto(
  val rootNode: StructureViewTreeElementDto,
  val nodes: RpcFlow<TreeNodesDto?>,
  val smartExpand: Boolean,
  val minimumAutoExpandDepth: Int,
  val rootNodeShown: Boolean,
  val actions: List<StructureTreeAction>
)

@ApiStatus.Internal
@Serializable
class StructureViewModelDtoId(val id: Int)

@ApiStatus.Internal
@Serializable
data class TreeNodesDto(
  val editorSelection: StructureViewTreeElementDto?,
  val nodes: List<StructureViewTreeElementDto>,
  val nodeProviders: List<NodeProviderTreeAction>,
)