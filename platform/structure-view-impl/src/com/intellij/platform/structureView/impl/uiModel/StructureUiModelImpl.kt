// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.impl.uiModel

import com.intellij.ide.vfs.rpcId
import com.intellij.openapi.application.UI
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.editorId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import com.intellij.platform.structureView.impl.StructureTreeApi
import com.intellij.platform.structureView.impl.StructureViewScopeHolder
import com.intellij.platform.structureView.impl.dto.StructureViewModelDto
import com.intellij.platform.structureView.impl.dto.StructureViewModelDtoId
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.containers.ContainerUtil
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.atomic.AtomicInteger

@ApiStatus.Internal
class StructureUiModelImpl(file: VirtualFile, project: Project, editor: Editor?) : StructureUiModel {
  internal var dto: StructureViewModelDto? = null
  internal val myUpdatePendingFlow: MutableStateFlow<Boolean> = MutableStateFlow(true)

  private val myModelListeners = ContainerUtil.createLockFreeCopyOnWriteList<StructureUiModelListener>()

  internal val dtoId: StructureViewModelDtoId = StructureViewModelDtoId(nextId.getAndIncrement())

  override val rootElement: StructureUiTreeElement = StructureUiTreeElement(null, null)

  private val cs = StructureViewScopeHolder.getInstance().cs.childScope("scope for ${file.name} structure view with id: $dtoId")

  private val myActions = hashSetOf<String>()
  private val nodeMap = HashMap<Int, StructureUiTreeElement>()

  private val nodeProviders = mutableListOf<NodeProviderTreeAction>()

  private val selection = MutableStateFlow<StructureUiTreeElement?>(null)

  init {
    cs.launch(start = CoroutineStart.UNDISPATCHED) {
      val modelFlow = StructureTreeApi.getInstance().getStructureViewModelFlow(editor?.editorId(), file.rpcId(), project.projectId(), dtoId)
      modelFlow.collect { model ->
        if (model == null) {
          logger.warn("No structure view model for $file")
          return@collect
        }
        dto = model
        rootElement.dto = model.rootNode
        rootElement.myChildren.clear()

        launch(Dispatchers.UI) {
          myModelListeners.forEach { it.onActionsChanged() }
        }

        model.nodes.toFlow().collect { nodesUpdate ->
          nodeMap.clear()
          nodeMap[0] = rootElement
          rootElement.myChildren.clear()

          if (nodesUpdate == null) {
            return@collect
          }

          nodeProviders.clear()
          nodeProviders.addAll(nodesUpdate.nodeProviders)

          for (nodeDto in nodesUpdate.nodes) {
            val parent = nodeMap[nodeDto.parentId] ?: run {
              logger.error("No parent for ${nodeDto.parentId} or it's not a backend one")
              continue
            }
            val node = StructureUiTreeElement(nodeDto, parent)
            nodeMap[nodeDto.id] = node
            parent.myChildren.add(node)
          }

          selection.emit(nodesUpdate.editorSelection?.let { nodeMap[it.id] })

          launch(Dispatchers.UI) {
            myModelListeners.forEach { it.onActionsChanged() }
            myModelListeners.forEach { it.onTreeChanged() }
            myUpdatePendingFlow.value = false
          }
        }
      }
    }
  }

  override val smartExpand: Boolean
    get() = dto?.smartExpand ?: false

  override val minimumAutoExpandDepth: Int
    get() = dto?.minimumAutoExpandDepth ?: 2

  override val editorSelection: StateFlow<StructureUiTreeElement?>
    get() = selection

  override fun isActionEnabled(action: StructureTreeAction): Boolean {
    return action.name in myActions
  }

  override fun setActionEnabled(action: StructureTreeAction, isEnabled: Boolean) {
    if (isEnabled == isActionEnabled(action)) return

    if (isEnabled) myActions.add(action.name) else myActions.remove(action.name)

    if (action is NodeProviderTreeAction || action is FilterTreeAction) return

    cs.launch {
      myUpdatePendingFlow.value = true
      StructureTreeApi.getInstance().setTreeActionState(dtoId, action.name, isEnabled)
    }
  }

  override fun getActions(): Collection<StructureTreeAction> = (dto?.actions ?: emptyList()) + nodeProviders

  override fun getUpdatePendingFlow(): Flow<Boolean> = myUpdatePendingFlow

  override fun addListener(listener: StructureUiModelListener) {
    myModelListeners.add(listener)
  }

  override fun dispose() {
    cs.cancel()
    nodeMap.clear()
    myModelListeners.clear()
    StructureViewScopeHolder.getInstance().cs.launch {
      StructureTreeApi.getInstance().structureViewModelDisposed(dtoId)
    }
  }

  @ApiStatus.Internal
  @Suppress("unused")
  fun dumpNodes(): String {
    val stringBuilder = StringBuilder()
    stringBuilder.append("Main nodes:").append('\n')
    stringBuilder.append(nodeMap.values.joinToString(", "))
    for (provider in nodeProviders) {
      stringBuilder.append("  provider: ${provider.javaClass.simpleName}").append('\n')
      stringBuilder.append("    nodes: ${provider.nodes.joinToString(", ")}").append('\n')
    }
    return stringBuilder.toString()
  }

  companion object {
    private var nextId = AtomicInteger()
    private val logger = logger<StructureUiModelImpl>()
  }
}