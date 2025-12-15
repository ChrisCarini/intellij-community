// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.frontend.uiModel

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
import com.intellij.platform.structureView.impl.uiModel.FilterTreeAction
import com.intellij.platform.structureView.impl.uiModel.StructureTreeAction
import com.intellij.platform.structureView.impl.uiModel.StructureUiTreeElement
import com.intellij.platform.structureView.frontend.uiModel.StructureUiTreeElementImpl.Companion.toUiElement
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.containers.ContainerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@ApiStatus.Internal
class StructureUiModelImpl(file: VirtualFile, project: Project, editor: Editor?) : StructureUiModel {
  override var dto: StructureViewModelDto? = null
  internal val myUpdatePendingFlow: MutableStateFlow<Boolean> = MutableStateFlow(true)

  private val myModelListeners = ContainerUtil.createLockFreeCopyOnWriteList<StructureUiModelListener>()

  internal val dtoId = nextId.getAndIncrement()

  override val rootElement: StructureUiTreeElementWrapper = StructureUiTreeElementWrapper()

  private val cs = StructureViewScopeHolder.getInstance().cs.childScope("scope for ${file.name} structure view with id: $dtoId")

  private val myActions = CopyOnWriteArrayList<String>()

  @Volatile
  private var myNodesMap: Map<Int, StructureUiTreeElementImpl> = emptyMap()

  @Volatile
  private var myNodeProviders: List<NodeProviderTreeActionImpl> = emptyList()

  private val selection = MutableStateFlow<StructureUiTreeElement?>(null)

  init {
    cs.launch {
      val model = StructureTreeApi.getInstance().getStructureViewModel(editor?.editorId(), file.rpcId(), project.projectId(), dtoId)

      if (model == null) {
        logger.warn("No structure view model for $file")
        launch(Dispatchers.UI) {
          myModelListeners.forEach { it.onTreeChanged() }
          myUpdatePendingFlow.value = false
        }
        return@launch
      }
      dto = model
      val rootNode = StructureUiTreeElementImpl(model.rootNode)

      launch(Dispatchers.UI) {
        myModelListeners.forEach { it.onActionsChanged() }
      }

      model.nodes.toFlow().collect { nodesUpdate ->
        val nodeMap = HashMap<Int, StructureUiTreeElementImpl>()
        nodeMap[0] = rootNode
        rootNode.myChildren.clear()

        if (nodesUpdate == null) {
          return@collect
        }


        val nodeProviders = nodesUpdate.nodeProviders.map {
          val nodes = run {
            val providerNodeMap = hashMapOf<Int, StructureUiTreeElementImpl>()
            val nodes = mutableListOf<StructureUiTreeElementImpl>()
            for (nodeDto in it.nodesDto) {
              val parent = providerNodeMap[nodeDto.parentId]
              val node = nodeDto.toUiElement(parent)
              providerNodeMap[nodeDto.id] = node
              if (parent == null) {
                nodes.add(node)
              }
              else {
                parent.myChildren.add(node)
              }
            }
            nodes
          }
          NodeProviderTreeActionImpl(it.actionType,
                                     it.name,
                                     it.presentation,
                                     it.isReverted,
                                     it.isEnabledByDefault,
                                     it.shortcutsIds,
                                     it.actionIdForShortcut,
                                     it.checkboxText,
                                     it.propertyName,
                                     nodes)
        }

        for (nodeDto in nodesUpdate.nodes) {
          val node = StructureUiTreeElementImpl(nodeDto)
          val parent = nodeMap[nodeDto.parentId] ?: run {
            logger.error("No parent for ${node.id} or it's not a backend one")
            continue
          }
          node.parent = parent
          parent.myChildren.add(node)
          nodeMap[nodeDto.id] = node
        }

        myNodesMap = nodeMap
        myNodeProviders = nodeProviders
        rootElement.setDelegate(rootNode)

        selection.emit(nodesUpdate.editorSelection?.let { s -> nodeMap[s.id] ?: myNodeProviders.firstNotNullOf { it.nodes[s.id] } })

        launch(Dispatchers.UI) {
          myModelListeners.forEach { it.onActionsChanged() }
          myModelListeners.forEach { it.onTreeChanged() }
          myUpdatePendingFlow.value = false
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

    if (action is NodeProviderTreeActionImpl || action is FilterTreeAction) return

    cs.launch {
      myUpdatePendingFlow.value = true
      StructureTreeApi.getInstance().setTreeActionState(dtoId, action.name, isEnabled)
    }
  }

  override fun getActions(): Collection<StructureTreeAction> = (dto?.actions ?: emptyList()) + myNodeProviders

  override fun getUpdatePendingFlow(): Flow<Boolean> = myUpdatePendingFlow

  override fun addListener(listener: StructureUiModelListener) {
    myModelListeners.add(listener)
  }

  override fun dispose() {
    cs.cancel()
    myNodesMap = emptyMap()
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
    stringBuilder.append(myNodesMap.values.joinToString("\n        "))
    for (provider in myNodeProviders) {
      stringBuilder.append("  provider: ${provider.javaClass.simpleName}").append('\n')
      stringBuilder.append("    nodes: ${provider.nodes.joinToString("\n        ")}").append('\n')
    }
    return stringBuilder.toString()
  }

  companion object {
    private var nextId = AtomicInteger()
    private val logger = logger<StructureUiModelImpl>()
  }
}