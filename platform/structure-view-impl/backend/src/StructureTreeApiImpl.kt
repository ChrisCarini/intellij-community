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
import com.intellij.ide.structureView.newStructureView.StructureViewComponent
import com.intellij.ide.structureView.newStructureView.StructureViewSelectVisitorState
import com.intellij.ide.structureView.newStructureView.TreeModelWrapper
import com.intellij.ide.structureView.newStructureView.getElementInfoProvider
import com.intellij.ide.util.*
import com.intellij.ide.util.FileStructurePopup.getDefaultValue
import com.intellij.ide.util.FileStructurePopup.saveState
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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IntRef
import com.intellij.openapi.util.PropertyOwner
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProject
import com.intellij.platform.rpc.UID
import com.intellij.platform.rpc.backend.RemoteApiProvider
import com.intellij.platform.structureView.impl.StructureTreeApi
import com.intellij.platform.structureView.impl.StructureViewScopeHolder
import com.intellij.platform.structureView.impl.dto.*
import com.intellij.platform.structureView.impl.uiModel.FilterTreeAction
import com.intellij.platform.structureView.impl.uiModel.NodeProviderTreeAction
import com.intellij.platform.structureView.impl.uiModel.StructureTreeAction
import com.intellij.psi.PsiElement
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.tree.TreeVisitor
import com.intellij.ui.tree.TreeVisitor.VisitThread
import com.intellij.util.Function
import com.intellij.util.ui.tree.TreeUtil
import fleet.rpc.core.toRpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.asDeferred
import org.jetbrains.concurrency.resolvedPromise
import java.util.concurrent.ConcurrentHashMap
import javax.swing.tree.TreePath

private class StructureTreeApiImpl : StructureTreeApi {
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

    if (treeModel == null) {
      return null
    }

    val wrapper = object : SmartTreeStructure(project, TreeModelWrapper(treeModel, BackendTreeActionOwner())) {
      override fun rebuildTree() {
        if (disposable.isDisposed()) return
        super.rebuildTree()
      }

      override fun createTree(): TreeElementWrapper {
        return StructureViewComponent.createWrapper(myProject, myModel.getRoot(), myModel)
      }
    }

    val myStructureTreeModel = StructureTreeModel<SmartTreeStructure>(wrapper, disposable)
    val myAsyncTreeModel = AsyncTreeModel(myStructureTreeModel, disposable)

    val sorterDtos = treeModel.sorters.toDto()

    val actionOwner = BackendTreeActionOwnerService.getInstance()
    for (sorter in treeModel.sorters) {
      actionOwner.setActionActive(sorter.name, getDefaultValue(sorter))
    }

    //todo for not a popup these don't have to implement FileStructureFilter
    val filters = treeModel.filters.filterIsInstance<FileStructureFilter>()

    val expandInfoProvider = treeModel as? ExpandInfoProvider
    val elementInfoProvider = getElementInfoProvider(treeModel)

    val root = readAction {
      createRootModel(wrapper.rootElement as TreeElementWrapper, expandInfoProvider, elementInfoProvider)
    }

    if (root == null) {
      return null
    }

    val nodesFlow = MutableStateFlow<TreeNodesDto?>(null)
    val structureViewEntry =
      StructureViewEntry(wrapper, myStructureTreeModel, treeModel, nodesFlow, myAsyncTreeModel, fileEditor, disposable)
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
        (filter as? TreeActionWithDefaultState)?.isEnabledByDefault ?: false,
        shortcut.toTypedArray(),
        actionIdForShortcut,
        filter.checkBoxText,
        (filter as? PropertyOwner)?.propertyName ?: filter.name
      )
    }

    StructureViewScopeHolder.getInstance().cs.launch {
      if (disposable.isDisposed) return@launch
      nodesFlow.value = try {
        val nodes = computeNodes(structureViewEntry)

        logger.debug {
          val time = System.currentTimeMillis() - startTime
          "Nodes for the structure model with id: $id were computed in $time ms"
        }
        nodes
      }
      catch (e: Throwable) {
        logger.error(e)
        null
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
      sorterDtos + filterDtos
    )
  }

  override suspend fun structureViewModelDisposed(id: Int) {
    withContext(Dispatchers.EDT) {
      logger.debug { "Structure model with id: $id was disposed" }
      val entry = structureViews.remove(id) ?: return@withContext
      Disposer.dispose(entry.disposable)
      entry.nodeToId.clear()
      entry.nodesFlow.value = null
    }
  }

  override suspend fun setTreeActionState(id: Int, actionName: String, isEnabled: Boolean) {
    if (BackendTreeActionOwnerService.getInstance().isActionActive(actionName) == isEnabled) return
    val time = System.currentTimeMillis()

    structureViews[id]?.let {
      BackendTreeActionOwnerService.getInstance().setActionActive(actionName, isEnabled)
      val action = it.treeModel.sorters.firstOrNull { it.name == actionName } ?: return@let
      saveState(action, isEnabled)
      it.wrapper.rebuildTree()
      it.structureTreeModel.invalidateAsync().asDeferred().await()
      it.nodesFlow.value = computeNodes(it)
    }
    logger.debug {
      val time = System.currentTimeMillis() - time
      "Tree action $actionName was set to $isEnabled in $time ms"
    }
  }

  private suspend fun getCorrectElementToSelect(
    asyncTreeModel: AsyncTreeModel,
    fileEditor: FileEditor?,
    treeModel: StructureViewModel,
  ): Any? {
    val (element, editorOffset) = readAction {
      treeModel.currentEditorElement to ((fileEditor as? TextEditor)?.getEditor()?.getCaretModel()?.offset ?: -1)
    }

    val state = StructureViewSelectVisitorState()
    val visitor: TreeVisitor = object : TreeVisitor {
      override fun visitThread(): VisitThread = VisitThread.BGT

      override fun visit(path: TreePath): TreeVisitor.Action {
        return StructureViewComponent.visitPathForElementSelection(path, element, editorOffset, state)
      }
    }

    val fallback = object : Function<TreePath?, Promise<Any?>> {
      override fun `fun`(path: TreePath?): Promise<Any?> {
        if (path == null && state.isOptimizationUsed()) {
          // Some structure views merge unrelated psi elements into a structure node (MarkdownStructureViewModel).
          // So turn off the isAncestor() optimization and retry once.
          state.disableOptimization()
          return asyncTreeModel.accept(visitor).thenAsync<Any?>(this)
        }
        else {
          val adjusted = path ?: state.bestMatch
          val value = if (path == null && adjusted != null && !state.isExactMatch && element is PsiElement) {
            val minChild = FileStructurePopup.findClosestPsiElement(element, adjusted, asyncTreeModel)
            StructureViewComponent.unwrapValue(minChild)
          }
          else {
            StructureViewComponent.unwrapValue(TreeUtil.getAbstractTreeNode(adjusted))
          }
          return if (adjusted == null) resolvedPromise(null) else resolvedPromise(value)
        }
      }
    }

    return asyncTreeModel.accept(visitor).thenAsync(fallback).asDeferred().await()
  }

  private suspend fun computeNodes(entry: StructureViewEntry): TreeNodesDto {
    val mainNodes = mutableListOf<StructureViewTreeElementDto>()
    //todo for not a popup these don't have to implement FileStructureNodeProvider
    val nodeProvidersMap = (entry.treeModel as? ProvidingTreeModel)?.nodeProviders?.filterIsInstance<FileStructureNodeProvider<*>>()
      ?.associate { it to mutableListOf<StructureViewTreeElementDto>() }
    val expandInfoProvider = entry.treeModel as? ExpandInfoProvider
    val elementInfoProvider = getElementInfoProvider(entry.treeModel)

    //todo for not a popup these don't have to implement FileStructureFilter
    val filters = entry.treeModel.filters.filterIsInstance<FileStructureFilter>()

    val currentEditorElement = getCorrectElementToSelect(entry.asyncTreeModel, entry.fileEditor, entry.treeModel)

    var editorSelection: StructureViewTreeElementDto? = null

    val visitor = object : TreeVisitor {
      override fun visitThread(): VisitThread = VisitThread.BGT

      override fun visit(path: TreePath): TreeVisitor.Action {
        val element = TreeUtil.getUserObject(path.lastPathComponent) as? TreeElementWrapper ?: return TreeVisitor.Action.CONTINUE
        // don't visit root here, it's sent separately
        if (element.parent == null) return TreeVisitor.Action.CONTINUE
        val maybeEditorSelection = processTreeElement(expandInfoProvider, elementInfoProvider, currentEditorElement,
                                                      element, mainNodes, nodeProvidersMap, filters, entry)
        if (maybeEditorSelection != null) {
          editorSelection = maybeEditorSelection
        }
        return TreeVisitor.Action.CONTINUE
      }
    }

    val dto = entry.asyncTreeModel.accept(visitor).then {
      TreeNodesDto(
        editorSelection,
        mainNodes,
        nodeProvidersMap?.entries?.map { (provider, nodes) ->
          val (actionIdForShortcut, shortcut) = if (provider is ActionShortcutProvider) {
            provider.actionIdForShortcut to emptyList()
          }
          else {
            null to provider.shortcut.map { it.rpcId() }
          }

          NodeProviderTreeAction(
            StructureTreeAction.Type.FILTER,
            provider.name,
            provider.presentation.toDto(),
            false,
            (provider as? TreeActionWithDefaultState)?.isEnabledByDefault ?: false,
            shortcut.toTypedArray(),
            actionIdForShortcut,
            provider.checkBoxText,
            (provider as? PropertyOwner)?.propertyName ?: provider.name,
            nodes,
          )
        } ?: emptyList(),
      )
    }.asDeferred().await()

    //todo for tw - proper selection logic, not just editor's element
    return dto
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
                         emptyList())
  }

  private fun processTreeElement(
    expandInfoProvider: ExpandInfoProvider?,
    elementInfoProvider: ElementInfoProvider?,
    currentEditorElement: Any?,
    wrapper: TreeElementWrapper,
    nodes: MutableList<StructureViewTreeElementDto>,
    nodeProvidersMap: Map<FileStructureNodeProvider<*>, MutableList<StructureViewTreeElementDto>>?,
    filters: List<FileStructureFilter>,
    structureViewEntry: StructureViewEntry,
  ): StructureViewTreeElementDto? {
    val element = wrapper.getValue() as? StructureViewTreeElement ?: return null

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
      filters.map { it.isVisible(element) }
    )

    if (wrapper.provider != null) {
      nodeProvidersMap?.get(wrapper.provider)?.add(model)
    }
    else {
      nodes.add(model)
    }

    return if (element.value == currentEditorElement) model else null
  }

  private data class StructureViewEntry(
    val wrapper: SmartTreeStructure,
    val structureTreeModel: StructureTreeModel<SmartTreeStructure>,
    val treeModel: StructureViewModel,
    val nodesFlow: MutableStateFlow<TreeNodesDto?>,
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