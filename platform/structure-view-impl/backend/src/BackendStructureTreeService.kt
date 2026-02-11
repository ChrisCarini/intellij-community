// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.backend

import com.intellij.ide.actions.ViewStructureAction
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModel.ElementInfoProvider
import com.intellij.ide.structureView.StructureViewModel.ExpandInfoProvider
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.logical.PhysicalAndLogicalStructureViewBuilder
import com.intellij.ide.structureView.newStructureView.StructureViewSelectVisitorState
import com.intellij.ide.structureView.newStructureView.TreeModelWrapper
import com.intellij.ide.util.FileStructureFilter
import com.intellij.ide.util.FileStructureNodeProvider
import com.intellij.ide.util.FileStructurePopup
import com.intellij.ide.structureView.newStructureView.StructureViewComponent
import com.intellij.ide.structureView.newStructureView.StructureViewUtil
import com.intellij.ide.structureView.newStructureView.getElementInfoProvider
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.smartTree.SmartTreeStructure
import com.intellij.ide.util.treeView.smartTree.TreeElementWrapper
import com.intellij.ide.util.treeView.smartTree.TreeStructureUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.client.ClientAppSession
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.rethrowControlFlowException
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IntRef
import com.intellij.platform.structureView.impl.DelegatingNodeProvider
import com.intellij.platform.structureView.impl.StructureViewScopeHolder
import com.intellij.platform.structureView.impl.dto.DeferredNodesDto
import com.intellij.platform.structureView.impl.dto.NodeProviderNodesDto
import com.intellij.platform.structureView.impl.dto.StructureViewDtoId
import com.intellij.platform.structureView.impl.dto.StructureViewModelDto
import com.intellij.platform.structureView.impl.dto.StructureViewTreeElementDto
import com.intellij.platform.structureView.impl.dto.TreeNodesDto
import com.intellij.psi.PsiElement
import com.intellij.ui.PlaceHolder
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.util.ui.tree.TreeUtil
import fleet.rpc.core.toRpc
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
import org.jetbrains.concurrency.asDeferred
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

internal class BackendStructureTreeService(private val session: ClientAppSession) {
  private val structureViews = ConcurrentHashMap<Int, StructureViewEntry>()

  val nextStructureViewId = AtomicInteger(0)

  fun getStructureViewEntry(id: StructureViewDtoId): StructureViewEntry? {
    return structureViews[id.id]
  }

  internal suspend fun getStructureViewModel(project: Project, fileEditor: FileEditor, navigationCallback: ((AbstractTreeNode<*>) -> Unit)?): StructureViewModelDto? {
    val id = nextStructureViewId.getAndIncrement()
    val startTime = System.currentTimeMillis()

    logger.debug { "Creating structure model for id: $id" }

    val disposable = Disposer.newDisposable(session, "Disposable for structure model with id: $id")

    val dto = try {
      val treeModel: StructureViewModel? = withContext(Dispatchers.EDT) {
        writeIntentReadAction {
          val structureViewBuilder = fileEditor.structureViewBuilder ?: return@writeIntentReadAction null
          when (structureViewBuilder) {
            is PhysicalAndLogicalStructureViewBuilder -> {
              val view = structureViewBuilder.createPhysicalStructureView(fileEditor, project)
              Disposer.register(disposable, view)
              ViewStructureAction.createStructureViewModel(project, fileEditor, view)
            }
            is TreeBasedStructureViewBuilder -> {
              structureViewBuilder.createStructureViewModel(EditorUtil.getEditorEx(fileEditor))
            }
            else -> {
              val view = structureViewBuilder.createStructureView(fileEditor, project)
              Disposer.register(disposable, view)
              ViewStructureAction.createStructureViewModel(project, fileEditor, view)
            }
          }
        }
      }

      if (treeModel == null) return null

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
                                     project,
                                     navigationCallback)

      structureViews[id] = entry

      val job = StructureViewScopeHolder.getInstance().cs.launch(CoroutineName("StructureView event processor for id: $id"),
                                                                 start = CoroutineStart.UNDISPATCHED) {
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
              try {
                entry.structureTreeModel.invoker.compute {
                  entry.nodeToId.clear()
                }.asDeferred().await()
              }
              finally {
                nodesFlow.emit(null)
                withContext(Dispatchers.EDT) {
                  Disposer.dispose(entry.disposable)
                  logger.debug { "Structure model with id: $id was disposed" }
                }
              }
            }
          }
        }
      }

      Disposer.register(disposable, Disposable { job.cancel() })

      val (root, actions) = try {
        myStructureTreeModel.invoker.compute {
          entry.wrapper.rebuildTree()

          val rootModel = createRootModel(wrapper.rootElement as TreeElementWrapper, treeModel as? ExpandInfoProvider, getElementInfoProvider(treeModel))
          val actions = createActionModels(treeModel)

          rootModel to actions
        }.asDeferred().await()
      }
      catch (e: Throwable) {
        rethrowControlFlowException(e)

        logger.error("Error creating structure model for file: $fileEditor", e)
        return null
      }

      if (root == null) {
        logger.error("Root model for structure model with id: $id (file $fileEditor) is null")
        return null
      }

      entry.requestFlow.tryEmit(StructureViewEvent.ComputeNodes)

      logger.debug {
        val time = System.currentTimeMillis() - startTime
        "Structure model with id: $id was created in $time ms"
      }

      StructureViewModelDto(
        StructureViewDtoId(id),
        root,
        nodesFlow.toRpc(),
        (treeModel as? ExpandInfoProvider)?.isSmartExpand ?: false,
        (treeModel as? ExpandInfoProvider)?.minimumAutoExpandDepth ?: 2,
        false, /*todo for tw*/
        actions
      )
    }
    catch (e: Throwable) {
      Disposer.dispose(disposable)
      structureViews.remove(id)

      rethrowControlFlowException(e)
      return null
    }

    return dto
  }

  suspend fun disposeStructureViewModel(id: StructureViewDtoId) {
    val entry = structureViews.remove(id.id) ?: return
    entry.requestFlow.emit(StructureViewEvent.Dispose)
  }

  private data class ComputeNodesResult(
    val editorSelectionId: Int?,
    val nodes: List<StructureViewTreeElementDto>,
    val nodeProviders: List<NodeProviderNodesDto>,
    val deferredProviderNodes: Deferred<DeferredNodesDto>,
  )

  private fun computeNodes(entryId: Int): ComputeNodesResult? {
    val entry = structureViews[entryId] ?: return null
    require(entry.structureTreeModel.invoker.isValidThread)

    val computeNodesStartTime = System.currentTimeMillis()

    logger.debug { "computeNodes: Starting computation for structure view entry with id: ${entry.idRef.get()}" }

    val mainNodes = mutableListOf<StructureViewTreeElementDto>()
    //todo for not a popup these don't have to implement FileStructureNodeProvider
    val nodeProvidersMap = getNodeProviders(entry.treeModel)?.filter { it !is DelegatingNodeProvider<*> }?.associate { it to mutableListOf<StructureViewTreeElementDto>() } ?: emptyMap()
    val expandInfoProvider = entry.treeModel as? ExpandInfoProvider
    val elementInfoProvider = getElementInfoProvider(entry.treeModel)

    //todo for not a popup these don't have to implement FileStructureFilter
    val filters = entry.treeModel.filters.filterIsInstance<FileStructureFilter>()

    logger.debug {
      val nodeProvidersCount = nodeProvidersMap.size
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

      val element = TreeUtil.getUserObject(it.lastPathComponent) as? TreeElementWrapper ?: return@visit false

      processTreeElement(expandInfoProvider, elementInfoProvider, element, mainNodes, nodeProvidersMap, filters, entry)
      false
    }

    logger.debug { "computeNodes: Tree traversal completed" }

    val selectedValue = processStateToGetSelectedValue(state, entry, currentEditorElement)

    val nodeProviders = nodeProvidersMap.entries.mapNotNull { (provider, nodes) ->
      val nodesLoaded = entry.backendActionOwner.isActionActive(provider)

      logger.info("Node provider ${provider.name} has nodes loaded: $nodesLoaded")

      if (!nodesLoaded) return@mapNotNull null
      NodeProviderNodesDto(
        provider.name,
        nodes,
      )
    }

    val selection = entry.nodeToId[selectedValue]

    val deferredNodeProviders = CompletableFuture<DeferredNodesDto>()

    if (nodeProvidersMap.keys.any { !entry.backendActionOwner.isActionActive(it) }) {
      entry.structureTreeModel.invoker.invokeLater {
        val entry = structureViews[entryId] ?: return@invokeLater
        // Check if any providers don't have their nodes loaded yet

        logger.debug { "Some providers don't have nodes loaded yet, rebuilding tree with all providers active" }

        // Enable all node providers
        entry.backendActionOwner.allNodeProvidersActive = true

        // Rebuild tree with all providers active
        entry.wrapper.rebuildTree()

        entry.structureTreeModel.invalidateAsync().handle { _, throwable ->
          if (throwable != null) {
            deferredNodeProviders.completeExceptionally(throwable)
          }
          // Compute nodes for ALL providers (not just inactive ones)
          // because previously active providers may have new nodes now
          if (entry.structureTreeModel.isDisposed) {
            logger.debug { "computeNodes: Skipping tree traversal for deferred nodes because tree is disposed" }
            return@handle
          }
          entry.structureTreeModel.invoker.invoke {
            if (entry.structureTreeModel.isDisposed) return@invoke
            logger.debug { "computeNodes: Tree traversal for deferred nodes started" }
            val allProviderNodes = computeAllProviderNodes(entry)
            logger.debug { "computeNodes: Tree traversal for deferred nodes completed" }
            deferredNodeProviders.complete(allProviderNodes)
          }.onError {
            logger.error("Error computing provider nodes", it)
            deferredNodeProviders.completeExceptionally(it)
          }
        }
      }.onError {
        logger.error("Error computing provider nodes", it)
        deferredNodeProviders.completeExceptionally(it)
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
    val providerNodesMap = getNodeProviders(entry.treeModel)?.filter { it !is DelegatingNodeProvider<*> }?.associateWith { mutableListOf<StructureViewTreeElementDto>() } ?: return null
    val expandInfoProvider = entry.treeModel as? ExpandInfoProvider
    val elementInfoProvider = getElementInfoProvider(entry.treeModel)
    val filters = entry.treeModel.filters.filterIsInstance<FileStructureFilter>()
    // Dummy list for non-provider elements (we only care about provider elements here)
    val mainNodes = mutableListOf<StructureViewTreeElementDto>()

    val root = entry.structureTreeModel.root ?: return null

    visit(root, entry.structureTreeModel, TreePath(root)) {
      val element = TreeUtil.getUserObject(it.lastPathComponent) as? TreeElementWrapper ?: return@visit false
      processTreeElement(expandInfoProvider, elementInfoProvider, element, mainNodes, providerNodesMap, filters, entry)
      false
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

  internal sealed class StructureViewEvent {
    data object ComputeNodes : StructureViewEvent()
    data object Dispose : StructureViewEvent()
  }

  internal data class StructureViewEntry(
    val wrapper: SmartTreeStructure,
    val structureTreeModel: StructureTreeModel<SmartTreeStructure>,
    val treeModel: StructureViewModel,
    val requestFlow: MutableSharedFlow<StructureViewEvent>,
    val backendActionOwner: BackendTreeActionOwner, // should only be accessed at StructureTreeModel.invoker
    val fileEditor: FileEditor,
    val disposable: Disposable,
    val project: Project,
    val navigationCallback: ((AbstractTreeNode<*>) -> Unit)?,
    val idRef: IntRef = IntRef(1), // should only be accessed at StructureTreeModel.invoker
    val nodeToId: MutableMap<Any, Int> = mutableMapOf(), // should only be accessed at StructureTreeModel.invoker
  )

  companion object {
    private val logger = logger<BackendStructureTreeService>()

    internal fun visit(element: TreeNode, model: StructureTreeModel<SmartTreeStructure>, path: TreePath, action: (TreePath) -> Boolean): Boolean {
      if (model.isDisposed) return true

      for (child in model.getChildren(element)) {
        val childPath = path.pathByAddingChild(child)
        if (action(childPath)) return true
        if (visit(child, model, childPath, action)) return true
      }
      return false
    }

    internal fun processStateToGetSelectedValue(state: StructureViewSelectVisitorState, entry: StructureViewEntry, currentEditorElement: Any?): Any? {
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
  }
}