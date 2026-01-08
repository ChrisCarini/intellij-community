// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.backend

import com.intellij.ide.rpc.rpcId
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModel.ElementInfoProvider
import com.intellij.ide.structureView.StructureViewModel.ExpandInfoProvider
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.StructureViewComposite
import com.intellij.ide.structureView.logical.StructureViewTab
import com.intellij.ide.structureView.newStructureView.*
import com.intellij.ide.util.*
import com.intellij.ide.util.FileStructurePopup.getDefaultValue
import com.intellij.ide.util.FileStructurePopup.saveState
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.smartTree.*
import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.impl.EditorId
import com.intellij.openapi.editor.impl.findEditor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IntRef
import com.intellij.openapi.util.PropertyOwner
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProject
import com.intellij.platform.rpc.UID
import com.intellij.platform.rpc.backend.RemoteApiProvider
import com.intellij.platform.structureView.impl.DelegatingNodeProvider
import com.intellij.platform.structureView.impl.StructureTreeApi
import com.intellij.platform.structureView.impl.StructureViewScopeHolder
import com.intellij.platform.structureView.impl.dto.NodeProviderNodesDto
import com.intellij.platform.structureView.impl.dto.StructureViewModelDto
import com.intellij.platform.structureView.impl.dto.StructureViewTreeElementDto
import com.intellij.platform.structureView.impl.dto.TreeNodesDto
import com.intellij.platform.structureView.impl.dto.toDto
import com.intellij.platform.structureView.impl.uiModel.CheckboxTreeActionImpl
import com.intellij.platform.structureView.impl.uiModel.FilterTreeAction
import com.intellij.platform.structureView.impl.uiModel.NodeProviderTreeAction
import com.intellij.platform.structureView.impl.uiModel.StructureTreeAction
import com.intellij.psi.PsiElement
import com.intellij.ui.PlaceHolder
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.tree.TreeVisitor
import com.intellij.ui.tree.TreeVisitor.VisitThread
import com.intellij.util.containers.nullize
import com.intellij.util.ui.tree.TreeUtil
import fleet.rpc.core.toRpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.concurrency.asDeferred
import org.jetbrains.concurrency.thenRun
import java.util.concurrent.ConcurrentHashMap
import javax.swing.tree.TreePath

internal class StructureTreeApiImpl : StructureTreeApi {
  private val structureViews = ConcurrentHashMap<UID, StructureViewEntry>()

  override suspend fun getStructureViewModel(
    editorId: EditorId?,
    fileId: VirtualFileId,
    projectId: ProjectId,
    id: Int,
  ): StructureViewModelDto? {
    val startTime = System.currentTimeMillis()

    logger.debug { "Creating structure model for id: $id" }

    val disposable = Disposer.newCheckedDisposable("Disposable for structure model with id: $id")
    val project = projectId.findProject()
    val file = fileId.virtualFile()
    if (file == null) {
      return null
    }
    val editor = editorId?.findEditor()
    val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(file)

    val treeModel = readAction {
      val structureViewBuilder = fileEditor?.structureViewBuilder
      if (structureViewBuilder == null) {
        return@readAction null
      }

      if (structureViewBuilder is TreeBasedStructureViewBuilder) {
        structureViewBuilder.createStructureViewModel(editor)
      }
      else {
        val structureView = structureViewBuilder.createStructureView(fileEditor, project)
        val physicalView = if (structureView is StructureViewComposite) {
          structureView.structureViews.firstOrNull { it.title == StructureViewTab.PHYSICAL.title }?.structureView
        }
        else {
          structureView
        }
        physicalView?.treeModel
      }
    }

    //todo flag for tw
    if (treeModel is PlaceHolder) {
      (treeModel as PlaceHolder).setPlace(TreeStructureUtil.PLACE)
    }

    if (treeModel == null) {
      return null
    }

    val backendActionOwner = BackendTreeActionOwner(allNodeProvidersActive = false)
    val wrapper = object : SmartTreeStructure(project, TreeModelWrapper(treeModel, backendActionOwner)) {
      override fun rebuildTree() {
        if (disposable.isDisposed()) return
        super.rebuildTree()

        val initStartTime = System.currentTimeMillis()
        ProgressManager.getInstance().computePrioritized<Unit, Throwable> {
          initChildren(rootElement as TreeElementWrapper)
        }

        logger.debug { "Init children for structure model with id: $id completed in ${System.currentTimeMillis() - initStartTime} ms" }
      }

      fun initChildren(element: AbstractTreeNode<*>) {
        val children = element.getChildren()
        for (child in children) {
          initChildren(child)
        }
      }

      override fun createTree(): TreeElementWrapper {
        return StructureViewComponent.createWrapper(myProject, myModel.getRoot(), myModel)
      }
    }

    val myStructureTreeModel = StructureTreeModel<SmartTreeStructure>(wrapper, disposable)
    val myAsyncTreeModel = AsyncTreeModel(myStructureTreeModel, disposable)

    val actionOwner = BackendTreeActionOwnerService.getInstance()

    initActionStates(treeModel, actionOwner)

    val sorterDtos = treeModel.sorters.toDto()

    val weirdNodeProviders = getDelegatingNodeProviders(treeModel)?.mapNotNull { provider ->
      if (provider !is FileStructureNodeProvider<*>) return@mapNotNull null
      val (actionIdForShortcut, shortcut) = if (provider is ActionShortcutProvider) {
        provider.actionIdForShortcut to emptyList()
      }
      else {
        null to provider.shortcut.map { it.rpcId() }
      }

      CheckboxTreeActionImpl(
        StructureTreeAction.Type.FILTER,
        provider.name,
        false,
        provider.presentation.toDto(),
        shortcut.toTypedArray(),
        actionIdForShortcut,
        provider.checkBoxText,
        getDefaultValue(provider),
      )
    } ?: emptyList()

    //todo for not a popup these don't have to implement FileStructureFilter
    val filters = treeModel.filters.filterIsInstance<FileStructureFilter>()

    val expandInfoProvider = treeModel as? ExpandInfoProvider
    val elementInfoProvider = getElementInfoProvider(treeModel)

    myStructureTreeModel.invoker.invoke {
      wrapper.rebuildTree()
    }.asDeferred().await()

    val root = readAction {
      createRootModel(wrapper.rootElement as TreeElementWrapper, expandInfoProvider, elementInfoProvider)
    }

    if (root == null) {
      return null
    }

    val nodesFlow = MutableStateFlow<TreeNodesDto?>(null)
    val deferredProviderNodesFlow = MutableStateFlow<List<NodeProviderNodesDto>?>(null)
    val structureViewEntry = StructureViewEntry(wrapper,
                                                myStructureTreeModel,
                                                treeModel,
                                                nodesFlow,
                                                deferredProviderNodesFlow,
                                                backendActionOwner,
                                                myAsyncTreeModel,
                                                fileEditor,
                                                disposable)
    structureViews[id] = structureViewEntry

    val filterDtos = filters.mapIndexed { index, filter ->
      val (actionIdForShortcut, shortcut) = if (filter is ActionShortcutProvider) {
        filter.actionIdForShortcut to emptyList()
      }
      else {
        null to filter.shortcut.map { it.rpcId() }
      }

      FilterTreeAction(
        index,
        StructureTreeAction.Type.FILTER,
        filter.name,
        filter.presentation.toDto(),
        filter.isReverted,
        getDefaultValue(filter),
        shortcut.toTypedArray(),
        actionIdForShortcut,
        filter.checkBoxText,
      )
    }

    StructureViewScopeHolder.getInstance().cs.launch {
      if (disposable.isDisposed) return@launch
      val nodesDto = try {
        val computeStartTime = System.currentTimeMillis()
        val nodes = computeNodes(structureViewEntry)

        logger.debug {
          val computeTime = System.currentTimeMillis() - computeStartTime
          val time = System.currentTimeMillis() - startTime
          "Nodes for the structure model with id: $id were computed in $computeTime ms, total time: $time ms"
        }
        nodes
      }
      catch (e: Throwable) {
        logger.error(e)
        null
      }

      nodesFlow.emit(nodesDto)

      // After initial nodes are emitted, check if any providers were inactive
      if (nodesDto != null) {
        try {
          // Check if any providers don't have their nodes loaded yet
          val hasUnloadedProviders = nodesDto.nodeProviders.any { !it.nodesLoaded }

          if (hasUnloadedProviders) {
            logger.debug { "Some providers don't have nodes loaded yet, rebuilding tree with all providers active" }

            // Enable all node providers
            backendActionOwner.allNodeProvidersActive = true

            // Rebuild tree with all providers active
            structureViewEntry.structureTreeModel.invoker.invoke {
              structureViewEntry.wrapper.rebuildTree()
            }.asDeferred().await()
            structureViewEntry.structureTreeModel.invalidateAsync().asDeferred().await()

            // Compute nodes for ALL providers (not just inactive ones)
            // because previously active providers may have new nodes now
            if (disposable.isDisposed) return@launch
            val allProviderNodes = computeAllProviderNodes(structureViewEntry)

            // Emit all provider nodes at once
            deferredProviderNodesFlow.emit(allProviderNodes)
          }
        }
        catch (e: Throwable) {
          logger.error("Error computing provider nodes", e)
        }
      }
    }

    logger.debug {
      val time = System.currentTimeMillis() - startTime
      "Structure model with id: $id was created in $time ms"
    }
    return StructureViewModelDto(
      root,
      nodesFlow.toRpc(),
      expandInfoProvider?.isSmartExpand ?: false,
      expandInfoProvider?.minimumAutoExpandDepth ?: 2,
      false, /*todo for tw*/
      sorterDtos + filterDtos + weirdNodeProviders
    )
  }

  override suspend fun structureViewModelDisposed(id: Int) {
    withContext(Dispatchers.EDT) {
      logger.debug { "Structure model with id: $id was disposed" }
      val entry = structureViews.remove(id) ?: return@withContext
      Disposer.dispose(entry.disposable)
      entry.nodeToId.clear()
      entry.nodesFlow.emit(null)
    }
  }

  override suspend fun setTreeActionState(id: Int, actionName: String, isEnabled: Boolean, autoClicked: Boolean) {
    if (BackendTreeActionOwnerService.getInstance().isActionActive(actionName) == isEnabled) return
    val time = System.currentTimeMillis()

    structureViews[id]?.let { entry ->
      BackendTreeActionOwnerService.getInstance().setActionActive(actionName, isEnabled)
      val nodeProviders =
        (entry.treeModel as? ProvidingTreeModel)?.nodeProviders?.filterIsInstance<FileStructureNodeProvider<*>>() ?: emptyList()
      val actions = nodeProviders + entry.treeModel.sorters + entry.treeModel.filters
      val action = actions.firstOrNull { it.name == actionName } ?: run {
        logger.error("Action $actionName not found in structure model with id: $id")
        return@let
      }
      if (!autoClicked) {
        saveState(action, isEnabled)
      }

      if (action is Filter || action is NodeProvider<*> && action !is DelegatingNodeProvider<*>) return@let

      entry.structureTreeModel.invoker.invoke {
        entry.wrapper.rebuildTree()
      }.thenRun {
        entry.structureTreeModel.invalidateAsync()
      }.asDeferred().await()

      val nodes = computeNodes(entry)
      entry.nodesFlow.emit(nodes)
    }
    logger.debug {
      val time = System.currentTimeMillis() - time
      "Tree action $actionName was set to $isEnabled in $time ms"
    }
  }

  private suspend fun computeNodes(entry: StructureViewEntry): TreeNodesDto {
    val computeNodesStartTime = System.currentTimeMillis()

    logger.debug { "computeNodes: Starting computation for structure view entry with id: ${entry.idRef.get()}" }

    val mainNodes = mutableListOf<StructureViewTreeElementDto>()
    //todo for not a popup these don't have to implement FileStructureNodeProvider
    val nodeProvidersMap = getNodeProviders(entry.treeModel)?.associate { it to mutableListOf<StructureViewTreeElementDto>() }
    val expandInfoProvider = entry.treeModel as? ExpandInfoProvider
    val elementInfoProvider = getElementInfoProvider(entry.treeModel)

    //todo for not a popup these don't have to implement FileStructureFilter
    val filters = entry.treeModel.filters.filterIsInstance<FileStructureFilter>()

    logger.debug {
      val nodeProvidersCount = nodeProvidersMap?.size ?: 0
      val filtersCount = filters.size
      "computeNodes: Setup - nodeProviders: $nodeProvidersCount, filters: $filtersCount, " +
      "expandInfoProvider: ${expandInfoProvider != null}, elementInfoProvider: ${elementInfoProvider != null}"
    }

    val (currentEditorElement, editorOffset) = readAction {
      entry.treeModel.currentEditorElement to ((entry.fileEditor as? TextEditor)?.getEditor()?.getCaretModel()?.offset ?: -1)
    }
    val state = StructureViewSelectVisitorState()

    val visitor = object : TreeVisitor {
      override fun visitThread(): VisitThread = VisitThread.BGT

      override fun visit(path: TreePath): TreeVisitor.Action {
        StructureViewComponent.visitPathForElementSelection(path, currentEditorElement, editorOffset, state)

        val element = TreeUtil.getUserObject(path.lastPathComponent) as? TreeElementWrapper ?: return TreeVisitor.Action.CONTINUE
        // don't visit root here, it's sent separately
        if (element.parent == null) return TreeVisitor.Action.CONTINUE
        processTreeElement(expandInfoProvider, elementInfoProvider, element, mainNodes, nodeProvidersMap, filters, entry)
        return TreeVisitor.Action.CONTINUE
      }
    }

    logger.debug { "computeNodes: Starting tree visitor traversal" }
    val visitorStartTime = System.currentTimeMillis()

    val deferredProviderNodesRpcFlow = entry.deferredProviderNodesFlow.toRpc()

    val dto = entry.asyncTreeModel.accept(visitor).then {
      val adjusted = state.bestMatch
      val value = if (adjusted != null && !state.isExactMatch && currentEditorElement is PsiElement) {
        val minChild = FileStructurePopup.findClosestPsiElement(currentEditorElement, adjusted, entry.asyncTreeModel)
        StructureViewComponent.unwrapValue(minChild)
      }
      else {
        StructureViewComponent.unwrapValue(TreeUtil.getAbstractTreeNode(adjusted))
      }
      val selection = if (adjusted == null) null else value

      TreeNodesDto(
        entry.nodeToId[selection],
        mainNodes,
        nodeProvidersMap?.entries?.map { (provider, nodes) ->
          val (actionIdForShortcut, shortcut) = if (provider is ActionShortcutProvider) {
            provider.actionIdForShortcut to emptyList()
          }
          else {
            null to provider.shortcut.map { it.rpcId() }
          }

          val nodesLoaded = entry.backendActionOwner.isActionActive(provider)

          logger.info("Node provider ${provider.name} has nodes loaded: $nodesLoaded")

          NodeProviderTreeAction(
            StructureTreeAction.Type.FILTER,
            provider.name,
            provider.presentation.toDto(),
            false,
            getDefaultValue(provider),
            shortcut.toTypedArray(),
            actionIdForShortcut,
            provider.checkBoxText,
            nodes,
            nodesLoaded,
          )
        } ?: emptyList(),
        deferredProviderNodesRpcFlow,
      )
    }.asDeferred().await()

    logger.debug {
      val visitorTime = System.currentTimeMillis() - visitorStartTime
      "computeNodes: Visitor completed in $visitorTime ms"
    }

    logger.debug {
      val totalComputeTime = System.currentTimeMillis() - computeNodesStartTime

      "computeNodes: total compute time: $totalComputeTime ms"
    }

    //todo for tw - proper selection logic, not just editor's element
    return dto
  }

  private suspend fun computeAllProviderNodes(entry: StructureViewEntry): List<NodeProviderNodesDto> {
    //all node providers are enabled anyway
    val providerNodesMap =
      getNodeProviders(entry.treeModel)?.nullize()?.associateWith { mutableListOf<StructureViewTreeElementDto>() } ?: return emptyList()
    val expandInfoProvider = entry.treeModel as? ExpandInfoProvider
    val elementInfoProvider = getElementInfoProvider(entry.treeModel)
    val filters = entry.treeModel.filters.filterIsInstance<FileStructureFilter>()
    // Dummy list for non-provider elements (we only care about provider elements here)
    val unusedNodes = mutableListOf<StructureViewTreeElementDto>()

    val visitor = object : TreeVisitor {
      override fun visitThread(): VisitThread = VisitThread.BGT

      override fun visit(path: TreePath): TreeVisitor.Action {
        val element = TreeUtil.getUserObject(path.lastPathComponent) as? TreeElementWrapper ?: return TreeVisitor.Action.CONTINUE
        // don't visit root here
        if (element.parent == null) return TreeVisitor.Action.CONTINUE
        // only process elements from the requested providers
        val provider = element.provider
        if (provider == null || provider !in providerNodesMap) return TreeVisitor.Action.CONTINUE

        processTreeElement(expandInfoProvider, elementInfoProvider, element, unusedNodes, providerNodesMap, filters, entry)
        return TreeVisitor.Action.CONTINUE
      }
    }

    entry.asyncTreeModel.accept(visitor).asDeferred().await()

    return providerNodesMap.map { (provider, nodes) ->
      logger.debug { "Computed ${nodes.size} nodes for provider: ${provider.name}" }
      NodeProviderNodesDto(provider.name, nodes)
    }
  }

  private fun createRootModel(
    wrapper: TreeElementWrapper,
    expandInfoProvider: ExpandInfoProvider?,
    elementInfoProvider: ElementInfoProvider?,
  ): StructureViewTreeElementDto? {
    val id = 0

    val element = wrapper.getValue() as? StructureViewTreeElement ?: return null


    return element.toDto(id,
                         -1,
                         0,
                         expandInfoProvider?.isAutoExpand(element),
                         elementInfoProvider?.isAlwaysShowsPlus(element),
                         elementInfoProvider?.isAlwaysLeaf(element),
                         StructureViewUtil.getSpeedSearchText(wrapper),
                         emptyList())
  }

  private fun processTreeElement(
    expandInfoProvider: ExpandInfoProvider?,
    elementInfoProvider: ElementInfoProvider?,
    wrapper: TreeElementWrapper,
    nodes: MutableList<StructureViewTreeElementDto>,
    nodeProvidersMap: Map<FileStructureNodeProvider<*>, MutableList<StructureViewTreeElementDto>>?,
    filters: List<FileStructureFilter>,
    structureViewEntry: StructureViewEntry,
  ) {
    val element = wrapper.getValue() as? StructureViewTreeElement ?: return

    val id = if (structureViewEntry.nodeToId.containsKey(element.value)) {
      structureViewEntry.nodeToId[element.value]!!
    }
    else {
      structureViewEntry.idRef.get().also {
        structureViewEntry.idRef.inc()
        structureViewEntry.nodeToId[element.value] = it
      }
    }

    val parentId = (wrapper.parent?.value as? StructureViewTreeElement)?.let { structureViewEntry.nodeToId[it.value] } ?: 0

    val model = element.toDto(
      id,
      parentId,
      wrapper.index,
      expandInfoProvider?.isAutoExpand(element),
      elementInfoProvider?.isAlwaysShowsPlus(element),
      elementInfoProvider?.isAlwaysLeaf(element),
      StructureViewUtil.getSpeedSearchText(wrapper),
      filters.map { it.isVisible(element) }
    )

    if (wrapper.provider != null) {
      nodeProvidersMap?.get(wrapper.provider)?.add(model) ?: nodes.add(model)
    }
    else {
      nodes.add(model)
    }
  }

  private fun getNodeProviders(treeModel: TreeModel): List<FileStructureNodeProvider<*>>? {
    return (treeModel as? ProvidingTreeModel)?.nodeProviders?.filterIsInstance<FileStructureNodeProvider<*>>()
      ?.filter { it !is DelegatingNodeProvider<*> }
  }


  private fun initActionStates(treeModel: TreeModel, actionOwner: BackendTreeActionOwnerService) {
    (treeModel as? ProvidingTreeModel)?.nodeProviders?.forEach { provider ->
      actionOwner.setActionActive(provider.name, getDefaultValue(provider))
    }

    treeModel.sorters.forEach { sorter ->
      actionOwner.setActionActive(sorter.name, getDefaultValue(sorter))
    }
  }

  private fun getDelegatingNodeProviders(treeModel: TreeModel): List<DelegatingNodeProvider<*>>? {
    return (treeModel as? ProvidingTreeModel)?.nodeProviders?.filterIsInstance<DelegatingNodeProvider<*>>()
  }

  private data class StructureViewEntry(
    val wrapper: SmartTreeStructure,
    val structureTreeModel: StructureTreeModel<SmartTreeStructure>,
    val treeModel: StructureViewModel,
    val nodesFlow: MutableStateFlow<TreeNodesDto?>,
    val deferredProviderNodesFlow: MutableStateFlow<List<NodeProviderNodesDto>?>,
    val backendActionOwner: BackendTreeActionOwner,
    val asyncTreeModel: AsyncTreeModel,
    val fileEditor: FileEditor?,
    val disposable: Disposable,
    val idRef: IntRef = IntRef(1),
    val nodeToId: MutableMap<Any, Int> = mutableMapOf(),
  )

  companion object {
    private val logger = logger<StructureTreeApiImpl>()
  }
}

@ApiStatus.Internal
class StructureTreeApiProvider : RemoteApiProvider {
  override fun RemoteApiProvider.Sink.remoteApis() {
    remoteApi(remoteApiDescriptor<StructureTreeApi>()) {
      StructureTreeApiImpl()
    }
  }
}