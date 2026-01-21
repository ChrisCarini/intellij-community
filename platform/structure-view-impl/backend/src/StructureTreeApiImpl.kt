// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.backend

import com.intellij.ide.actions.ViewStructureAction
import com.intellij.ide.rpc.rpcId
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModel.ElementInfoProvider
import com.intellij.ide.structureView.StructureViewModel.ExpandInfoProvider
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.logical.PhysicalAndLogicalStructureViewBuilder
import com.intellij.ide.structureView.newStructureView.*
import com.intellij.ide.util.*
import com.intellij.ide.util.FileStructurePopup.getDefaultValue
import com.intellij.ide.util.FileStructurePopup.saveState
import com.intellij.ide.util.treeView.smartTree.*
import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.virtualFile
import com.intellij.internal.statistic.collectors.fus.actions.persistence.ActionsCollectorImpl
import com.intellij.internal.statistic.collectors.fus.actions.persistence.ActionsEventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IntRef
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProject
import com.intellij.platform.rpc.backend.RemoteApiProvider
import com.intellij.platform.structureView.impl.DelegatingNodeProvider
import com.intellij.platform.structureView.impl.StructureTreeApi
import com.intellij.platform.structureView.impl.StructureViewScopeHolder
import com.intellij.platform.structureView.impl.dto.*
import com.intellij.platform.structureView.impl.uiModel.NodeProviderTreeAction
import com.intellij.platform.structureView.impl.uiModel.StructureTreeAction
import com.intellij.psi.PsiElement
import com.intellij.ui.PlaceHolder
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.util.containers.nullize
import com.intellij.util.ui.tree.TreeUtil
import fleet.rpc.core.toRpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import org.jetbrains.concurrency.asDeferred
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

internal class StructureTreeApiImpl : StructureTreeApi {
  private val structureViews = ConcurrentHashMap<Int, StructureViewEntry>()

  override suspend fun getStructureViewModel(
    fileId: VirtualFileId,
    projectId: ProjectId,
    id: Int,
  ): StructureViewModelDto? {
    val startTime = System.currentTimeMillis()

    logger.debug { "Creating structure model for id: $id" }

    val disposable = Disposer.newDisposable("Disposable for structure model with id: $id")
    val project = projectId.findProject()
    val file = fileId.virtualFile()
    if (file == null) {
      return null
    }
    val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(file) ?: return null

    val (structureView, treeModel) = withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        val structureViewBuilder = fileEditor.structureViewBuilder ?: return@writeIntentReadAction null to null
        when (structureViewBuilder) {
          is PhysicalAndLogicalStructureViewBuilder -> {
            val view = structureViewBuilder.createPhysicalStructureView(fileEditor, project)
            view to ViewStructureAction.createStructureViewModel(project, fileEditor, view)
          }
          is TreeBasedStructureViewBuilder -> {
            return@writeIntentReadAction null to structureViewBuilder.createStructureViewModel(EditorUtil.getEditorEx(fileEditor))
          }
          else -> {
            val view = structureViewBuilder.createStructureView(fileEditor, project)
            view to ViewStructureAction.createStructureViewModel(project, fileEditor, view)
          }
        }
      }
    }

    if (treeModel == null) return null
    if (structureView != null) Disposer.register(disposable, structureView)

    //todo flag for tw
    (treeModel as? PlaceHolder)?.setPlace(TreeStructureUtil.PLACE)

    val backendActionOwner = BackendTreeActionOwner(allNodeProvidersActive = false)
    val wrapper = object : SmartTreeStructure(project, TreeModelWrapper(treeModel, backendActionOwner)) {
      override fun rebuildTree() {
        if (!structureViews.containsKey(id)) return
        super.rebuildTree()
      }

      override fun createTree(): TreeElementWrapper {
        return StructureViewComponent.createWrapper(myProject, myModel.getRoot(), myModel)
      }
    }

    val myStructureTreeModel = StructureTreeModel<SmartTreeStructure>(wrapper, disposable)

    val requestFlow = MutableSharedFlow<StructureViewEvent>(
      extraBufferCapacity = 1,
      onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val nodesFlow = MutableStateFlow<TreeNodesDto?>(null)
    val entry = StructureViewEntry(wrapper,
                                   myStructureTreeModel,
                                   treeModel,
                                   requestFlow,
                                   backendActionOwner,
                                   fileEditor,
                                   disposable,
                                   project)

    structureViews[id] = entry

    val job = StructureViewScopeHolder.getInstance().cs.launch(CoroutineName("StructureView event processor for id: $id"), start = CoroutineStart.UNDISPATCHED) {
      entry.requestFlow.collectLatest { event ->
        when (event) {
          is StructureViewEvent.ComputeNodes -> {
            val computeStartTime = System.currentTimeMillis()
            val nodes = entry.structureTreeModel.invoker.compute {
              computeNodes(id)
            }.asDeferred().await()

            logger.debug {
              val computeTime = System.currentTimeMillis() - computeStartTime
              "Nodes for the structure model with id: $id were computed in $computeTime ms"
            }

            val nodesDto = nodes?.let {
              TreeNodesDto(it.editorSelectionId, it.nodes, it.nodeProviders, it.deferredProviderNodes)
            }
            nodesFlow.emit(nodesDto)
          }
          is StructureViewEvent.Dispose -> {
            entry.structureTreeModel.invoker.compute {
              entry.nodeToId.clear()
            }.asDeferred().await()
            nodesFlow.emit(null)
            withContext(Dispatchers.EDT) {
              Disposer.dispose(entry.disposable)
              logger.debug { "Structure model with id: $id was disposed" }
            }
          }
        }
      }
    }

    Disposer.register(disposable, Disposable { job.cancel() })

    val (root, actions) = myStructureTreeModel.invoker.compute {
      entry.wrapper.rebuildTree()

      val rootModel = createRootModel(wrapper.rootElement as TreeElementWrapper, treeModel as? ExpandInfoProvider, getElementInfoProvider(treeModel))
      val actions = createAllActionsButNonDelegatedNodeProviderDtos(treeModel)

      rootModel to actions
    }.asDeferred().await()

    if (root == null) {
      logger.error("Root model for structure model with id: $id (file $file) is null")
      return null
    }

    entry.requestFlow.tryEmit(StructureViewEvent.ComputeNodes)

    logger.debug {
      val time = System.currentTimeMillis() - startTime
      "Structure model with id: $id was created in $time ms"
    }
    return StructureViewModelDto(
      root,
      nodesFlow.toRpc(),
      (treeModel as? ExpandInfoProvider)?.isSmartExpand ?: false,
      (treeModel as? ExpandInfoProvider)?.minimumAutoExpandDepth ?: 2,
      false, /*todo for tw*/
      actions
    )
  }

  override suspend fun structureViewModelDisposed(id: Int) {
    val entry = structureViews.remove(id) ?: return
    entry.requestFlow.emit(StructureViewEvent.Dispose)
  }

  override suspend fun setTreeActionState(id: Int, actionName: String, isEnabled: Boolean, autoClicked: Boolean) {
    structureViews[id]?.let { entry ->
      entry.structureTreeModel.invoker.invoke {
        val nodeProviders = (entry.treeModel as? ProvidingTreeModel)?.nodeProviders?.filterIsInstance<FileStructureNodeProvider<*>>() ?: emptyList()
        val actions = nodeProviders + entry.treeModel.sorters + entry.treeModel.filters
        val action = actions.firstOrNull { it.name == actionName } ?: run {
          logger.error("Action $actionName not found in structure model with id: $id")
          return@invoke
        }

        logFileStructureCheckboxClick(action, entry.fileEditor, entry.project)

        // Store autoclicked state in the action owner, persist user-initiated changes
        if (autoClicked) {
          entry.backendActionOwner.setAutoclickedActionState(actionName, isEnabled)
        } else {
          entry.backendActionOwner.clearAutoclickedActionState(actionName)
          saveState(action, isEnabled)
        }

        if (action is Filter || action is NodeProvider<*> && action !is DelegatingNodeProvider<*>) return@invoke

        entry.wrapper.rebuildTree()
        entry.structureTreeModel.invalidateAsync().thenRun {
          if (entry.structureTreeModel.isDisposed) return@thenRun
          entry.requestFlow.tryEmit(StructureViewEvent.ComputeNodes)
        }
      }
    }
  }

  @TestOnly
  override suspend fun getNewSelection(id: Int): Int? {
    val entry = structureViews[id] ?: return null

    val selection = entry.structureTreeModel.invoker.compute {
      val (currentEditorElement, editorOffset) = entry.treeModel.currentEditorElement to ((entry.fileEditor as? TextEditor)?.getEditor()
                                                                                            ?.getCaretModel()?.offset ?: -1)
      val state = StructureViewSelectVisitorState()
      val root = entry.structureTreeModel.root ?: return@compute null
      visit(root, entry.structureTreeModel, TreePath(root)) {
        StructureViewComponent.visitPathForElementSelection(it, currentEditorElement, editorOffset, state)
      }


      val selectedValue = processStateToGetSelectedValue(state, entry, currentEditorElement)
      entry.nodeToId[selectedValue]
    }.asDeferred().await()


    return selection
  }

  private fun processStateToGetSelectedValue(state: StructureViewSelectVisitorState, entry: StructureViewEntry, currentEditorElement: Any?): Any? {
    val adjusted = state.bestMatch
    val value = if (adjusted != null && !state.isExactMatch && currentEditorElement is PsiElement) {
      val minChild = FileStructurePopup.findClosestPsiElement(currentEditorElement, adjusted, entry.structureTreeModel)
      if (minChild != null) StructureViewComponent.unwrapValue(minChild) else StructureViewComponent.unwrapValue(TreeUtil.getAbstractTreeNode(adjusted))
    }
    else {
      StructureViewComponent.unwrapValue(TreeUtil.getAbstractTreeNode(adjusted))
    }
    return if (adjusted == null) null else value
  }

  private data class ComputeNodesResult(
    val editorSelectionId: Int?,
    val nodes: List<StructureViewTreeElementDto>,
    val nodeProviders: List<NodeProviderTreeAction>,
    val deferredProviderNodes: Deferred<DeferredNodesDto>,
  )

  private fun computeNodes(entryId: Int): ComputeNodesResult? {
    val entry = structureViews[entryId] ?: return null
    require(entry.structureTreeModel.invoker.isValidThread)

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

    val (currentEditorElement, editorOffset) = entry.treeModel.currentEditorElement to ((entry.fileEditor as? TextEditor)?.getEditor()
                                                                                          ?.getCaretModel()?.offset ?: -1)
    val state = StructureViewSelectVisitorState()

    logger.debug { "computeNodes: Starting tree traversal" }
    val visitorStartTime = System.currentTimeMillis()

    val root = entry.structureTreeModel.root ?: return null
    visit(root, entry.structureTreeModel, TreePath(root)) {
      StructureViewComponent.visitPathForElementSelection(it, currentEditorElement, editorOffset, state)

      val element = TreeUtil.getUserObject(it.lastPathComponent) as? TreeElementWrapper ?: return@visit

      processTreeElement(expandInfoProvider, elementInfoProvider, element, mainNodes, nodeProvidersMap, filters, entry)
    }

    logger.debug { "computeNodes: Tree traversal completed" }

     val selectedValue = processStateToGetSelectedValue(state, entry, currentEditorElement)

    val nodeProviders = nodeProvidersMap?.entries?.map { (provider, nodes) ->
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
    } ?: emptyList()

    val selection = entry.nodeToId[selectedValue]

    val deferredNodeProviders = CompletableFuture<DeferredNodesDto>()

    if (nodeProviders.any { !it.nodesLoaded }) {
      entry.structureTreeModel.invoker.invokeLater {
        val entry = structureViews[entryId] ?: return@invokeLater
        try {
          // Check if any providers don't have their nodes loaded yet

          logger.debug { "Some providers don't have nodes loaded yet, rebuilding tree with all providers active" }

          // Enable all node providers
          entry.backendActionOwner.allNodeProvidersActive = true

          // Rebuild tree with all providers active

          entry.wrapper.rebuildTree()

          entry.structureTreeModel.invalidateAsync().thenRun {
            // Compute nodes for ALL providers (not just inactive ones)
            // because previously active providers may have new nodes now
            if (entry.structureTreeModel.isDisposed) {
              logger.debug { "computeNodes: Skipping tree traversal for deferred nodes because tree is disposed" }
              return@thenRun
            }
            entry.structureTreeModel.invoker.invoke {
              if (entry.structureTreeModel.isDisposed) return@invoke
              logger.debug { "computeNodes: Tree traversal for deferred nodes started" }
              val allProviderNodes = computeAllProviderNodes(entry)
              logger.debug { "computeNodes: Tree traversal for deferred nodes completed" }
              deferredNodeProviders.complete(allProviderNodes)
            }
          }
        }
        catch (e: Throwable) {
          logger.error("Error computing provider nodes", e)
        }
      }
    }
    else {
      deferredNodeProviders.complete(null)
    }

    logger.debug {
      val visitorTime = System.currentTimeMillis() - visitorStartTime
      "computeNodes: Visitor completed in $visitorTime ms"
    }

    logger.debug {
      val totalComputeTime = System.currentTimeMillis() - computeNodesStartTime

      "computeNodes: total compute time: $totalComputeTime ms"
    }

    //todo for tw - proper selection logic, not just editor's element
    return ComputeNodesResult(
      selection,
      mainNodes,
      nodeProviders,
      deferredNodeProviders.asDeferred()
    )
  }

  private fun computeAllProviderNodes(entry: StructureViewEntry): DeferredNodesDto? {
    require(entry.structureTreeModel.invoker.isValidThread)

    //all node providers are enabled anyway
    val providerNodesMap =
      getNodeProviders(entry.treeModel)?.nullize()?.associateWith { mutableListOf<StructureViewTreeElementDto>() } ?: return null
    val expandInfoProvider = entry.treeModel as? ExpandInfoProvider
    val elementInfoProvider = getElementInfoProvider(entry.treeModel)
    val filters = entry.treeModel.filters.filterIsInstance<FileStructureFilter>()
    // Dummy list for non-provider elements (we only care about provider elements here)
    val mainNodes = mutableListOf<StructureViewTreeElementDto>()

    val root = entry.structureTreeModel.root ?: return null

    visit(root, entry.structureTreeModel, TreePath(root)) {
      val element = TreeUtil.getUserObject(it.lastPathComponent) as? TreeElementWrapper ?: return@visit
      processTreeElement(expandInfoProvider, elementInfoProvider, element, mainNodes, providerNodesMap, filters, entry)
    }

    return DeferredNodesDto(
      providerNodesMap.map { (provider, nodes) ->
        logger.debug { "Computed ${nodes.size} nodes for provider: ${provider.name}" }
        NodeProviderNodesDto(provider.name, nodes)
      },
      mainNodes
    )
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
    require(structureViewEntry.structureTreeModel.invoker.isValidThread)

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

  private fun visit(element: TreeNode, model: StructureTreeModel<SmartTreeStructure>, path: TreePath, action: (TreePath) -> Unit) {
    if (model.isDisposed) return

    for (child in model.getChildren(element)) {
      val childPath = path.pathByAddingChild(child)
      action(childPath)
      visit(child, model, childPath, action)
    }
  }

  private fun logFileStructureCheckboxClick(action: TreeAction, fileEditor: FileEditor, project: Project) {
    val fileType = fileEditor.getFile().fileType
    val language = if (fileType is LanguageFileType) fileType.language else null

    ActionsCollectorImpl.recordActionInvoked(project) {
      add(EventFields.PluginInfoFromInstance.with(action))
      add(EventFields.ActionPlace.with(ActionPlaces.FILE_STRUCTURE_POPUP))
      add(EventFields.CurrentFile.with(language))
      add(ActionsEventLogGroup.ACTION_CLASS.with(action.javaClass))
      add(ActionsEventLogGroup.ACTION_ID.with(action.javaClass.getName()))
    }
  }

  private sealed class StructureViewEvent {
    data object ComputeNodes : StructureViewEvent()
    data object Dispose : StructureViewEvent()
  }

  private data class StructureViewEntry(
    val wrapper: SmartTreeStructure,
    val structureTreeModel: StructureTreeModel<SmartTreeStructure>,
    val treeModel: StructureViewModel,
    val requestFlow: MutableSharedFlow<StructureViewEvent>,
    val backendActionOwner: BackendTreeActionOwner, // should only be accessed at StructureTreeModel.invoker
    val fileEditor: FileEditor,
    val disposable: Disposable,
    val project: Project,
    val idRef: IntRef = IntRef(1), // should only be accessed at StructureTreeModel.invoker
    val nodeToId: MutableMap<Any, Int> = mutableMapOf(), // should only be accessed at StructureTreeModel.invoker
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