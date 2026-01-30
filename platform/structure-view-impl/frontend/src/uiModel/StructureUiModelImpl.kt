// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.frontend.uiModel

import com.intellij.ide.vfs.rpcId
import com.intellij.openapi.application.UI
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import com.intellij.platform.structureView.impl.StructureTreeApi
import com.intellij.platform.structureView.impl.StructureViewScopeHolder
import com.intellij.platform.structureView.impl.dto.StructureViewModelDto
import com.intellij.platform.structureView.impl.uiModel.StructureUiTreeElement
import com.intellij.platform.structureView.frontend.uiModel.StructureUiTreeElementImpl.Companion.toUiElement
import com.intellij.platform.structureView.impl.dto.StructureViewTreeElementDto
import com.intellij.ide.rpc.rpcId
import com.intellij.platform.structureView.impl.dto.NodeProviderNodesDto
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.containers.ContainerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@ApiStatus.Internal
class StructureUiModelImpl(fileEditor: FileEditor, file: VirtualFile, project: Project) : StructureUiModel {
  override var dto: StructureViewModelDto? = null
  internal val myUpdatePendingFlow: MutableStateFlow<Boolean> = MutableStateFlow(true)

  private val myModelListeners = ContainerUtil.createLockFreeCopyOnWriteList<StructureUiModelListener>()

  internal val dtoId = nextId.getAndIncrement()

  override val rootElement: StructureUiTreeElementWrapper = StructureUiTreeElementWrapper()

  private val cs = StructureViewScopeHolder.getInstance().cs.childScope("scope for ${file.name} structure view with id: $dtoId")

  private val myEnabledActionNames = CopyOnWriteArrayList<String>()

  @Volatile
  private var myActions: List<StructureTreeAction> = emptyList()

  private val selection = MutableStateFlow<StructureUiTreeElement?>(null)

  @Volatile
  private var rebuildTreeOnDeferredNodes: Boolean = false

  init {
    cs.launch {
      val model = StructureTreeApi.getInstance().getStructureViewModel(fileEditor.rpcId(), file.rpcId(), project.projectId(), dtoId)

      if (model == null) {
        logger.warn("No structure view model for $file")
        withContext(Dispatchers.UI) {
          myModelListeners.forEach { it.onTreeChanged() }
          myUpdatePendingFlow.value = false
        }
        return@launch
      }
      dto = model

      // Convert DTOs to impl classes
      myActions = model.actions.map { it.toImpl() }

      myEnabledActionNames.addAll(myActions.filter {
        if (it is FilterTreeAction) {
          it.isReverted != it.isEnabledByDefault
        }
        else {
          it.isEnabledByDefault
        }
      }.map { it.name })

      model.nodes.toFlow().collect { nodesUpdate ->
        if (nodesUpdate == null) {
          return@collect
        }

        applyNodesModel(model.rootNode,
                        nodesUpdate.nodeProviders,
                        nodesUpdate.nodes,
                        nodesUpdate.editorSelectionId)

        withContext(Dispatchers.UI) {
          myModelListeners.forEach { it.onActionsChanged() }
          myModelListeners.forEach { it.onTreeChanged() }
          myUpdatePendingFlow.value = false
        }

        val deferredNodes = try {
          nodesUpdate.deferredProviderNodes.await()
        }
        catch (e: Throwable) {
          logger.error("Error computing provider nodes", e)
          rebuildTreeOnDeferredNodes = false
          withContext(Dispatchers.UI) {
            myUpdatePendingFlow.value = false
          }
          return@collect
        }

        if (deferredNodes != null) {
          applyNodesModel(model.rootNode,
                          deferredNodes.nodeProviders,
                          deferredNodes.nodes,
                          nodesUpdate.editorSelectionId)
        }

        // If an incomplete node provider was enabled while waiting for deferred nodes, rebuild tree now
        if (rebuildTreeOnDeferredNodes) {
          if (deferredNodes == null || deferredNodes.nodeProviders.isEmpty()) {
            logger.error("Deferred provider nodes list is empty, but rebuildTreeOnDeferredNodes is true")
          }
          rebuildTreeOnDeferredNodes = false
          withContext(Dispatchers.UI) {
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
    return action.name in myEnabledActionNames
  }

  override fun setActionEnabled(action: StructureTreeAction, isEnabled: Boolean, isAutoClicked: Boolean) {
    if (isEnabled == isActionEnabled(action)) return

    if (isEnabled) {
      myEnabledActionNames.add(action.name)
    }
    else {
      myEnabledActionNames.remove(action.name)
    }

    if (action is NodeProviderTreeAction) {
      // If enabling an incomplete node provider, mark as pending and request tree rebuild when nodes arrive
      if (isEnabled && !action.nodesLoaded) {
        myUpdatePendingFlow.value = true
        rebuildTreeOnDeferredNodes = true
      }
    }
    else if (action !is FilterTreeAction) {
      myUpdatePendingFlow.value = true
    }

    cs.launch {
      StructureTreeApi.getInstance().setTreeActionState(dtoId, action.name, isEnabled, isAutoClicked)
    }
  }

  private fun applyNodesModel(
    rootDto: StructureViewTreeElementDto,
    nodeProviders: List<NodeProviderNodesDto>,
    nodes: List<StructureViewTreeElementDto>,
    editorSelectionId: Int?,
  ) {
    var selectionElement: StructureUiTreeElement? = null

    for (providerDto in nodeProviders) {
      val provider = myActions.find { it.name == providerDto.providerName } as? NodeProviderTreeAction
      if (provider == null) {
        logger.warn("No provider found for name: ${providerDto.providerName}")
        continue
      }

      val (selection, nodes) = convertNodesForProvider(editorSelectionId, providerDto.nodes)

      if (selection != null) selectionElement = selection

      provider.setNodes(nodes)
    }

    val rootNode = StructureUiTreeElementImpl(rootDto)
    val nodeMap = HashMap<Int, StructureUiTreeElementImpl>()
    nodeMap[0] = rootNode

    for (nodeDto in nodes) {
      val node = StructureUiTreeElementImpl(nodeDto)
      val parent = nodeMap[nodeDto.parentId] ?: run {
        logger.error("No parent for ${node.id} or it's not a backend one")
        continue
      }
      node.parent = parent
      if (nodeDto.id == editorSelectionId) selectionElement = node
      parent.myChildren.add(node)
      nodeMap[nodeDto.id] = node
    }

    rootElement.setDelegate(rootNode)
    selection.value = selectionElement
  }

  override fun getActions(): Collection<StructureTreeAction> = myActions

  override fun getUpdatePendingFlow(): StateFlow<Boolean> = myUpdatePendingFlow

  override fun addListener(listener: StructureUiModelListener) {
    myModelListeners.add(listener)
  }

  override fun dispose() {
    cs.cancel()
    myModelListeners.clear()
    StructureViewScopeHolder.getInstance().cs.launch {
      StructureTreeApi.getInstance().structureViewModelDisposed(dtoId)
    }
  }

  private fun convertNodesForProvider(
    editorSelectionId: Int?,
    nodesDto: List<StructureViewTreeElementDto>,
  ): Pair<StructureUiTreeElementImpl?, List<StructureUiTreeElementImpl>> {
    val providerNodeMap = hashMapOf<Int, StructureUiTreeElementImpl>()
    val nodes = mutableListOf<StructureUiTreeElementImpl>()
    var selectionElement: StructureUiTreeElementImpl? = null
    for (nodeDto in nodesDto) {
      val parent = providerNodeMap[nodeDto.parentId]
      val node = nodeDto.toUiElement(parent)
      providerNodeMap[nodeDto.id] = node
      if (nodeDto.id == editorSelectionId) selectionElement = node
      if (parent == null) {
        nodes.add(node)
      }
      else {
        parent.myChildren.add(node)
      }
    }
    return selectionElement to nodes
  }

  companion object {
    private var nextId = AtomicInteger()
    private val logger = logger<StructureUiModelImpl>()
  }
}