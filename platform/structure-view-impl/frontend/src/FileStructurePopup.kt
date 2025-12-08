// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.frontend

import com.intellij.CommonBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.ide.*
import com.intellij.ide.dnd.aware.DnDAwareTree
import com.intellij.ide.structureView.newStructureView.StructurePopup
import com.intellij.ide.structureView.newStructureView.TreeActionsOwner
import com.intellij.ide.ui.UISettingsListener
import com.intellij.ide.util.*
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.ide.util.treeView.smartTree.TreeStructureUtil
import com.intellij.internal.statistic.collectors.fus.actions.persistence.ActionsCollectorImpl
import com.intellij.internal.statistic.collectors.fus.actions.persistence.ActionsEventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.lang.LangBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.UiDataProvider.Companion.wrapComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.platform.structureView.impl.StructureViewScopeHolder
import com.intellij.platform.structureView.impl.uiModel.*
import com.intellij.platform.util.coroutines.childScope
import com.intellij.pom.Navigatable
import com.intellij.ui.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.popup.PopupUpdateProcessor
import com.intellij.ui.scale.JBUIScale.scale
import com.intellij.ui.speedSearch.ElementFilter
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.tree.TreeVisitor
import com.intellij.ui.tree.TreeVisitor.VisitThread
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.treeStructure.filtered.FilteringTreeStructure
import com.intellij.ui.treeStructure.filtered.FilteringTreeStructure.FilteringNode
import com.intellij.util.Alarm
import com.intellij.util.Function
import com.intellij.util.containers.nullize
import com.intellij.util.ui.EdtInvocationManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.SpeedSearchAdvertiser
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.launch
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.TestOnly
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.rejectedPromise
import org.jetbrains.concurrency.resolvedPromise
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import javax.swing.*
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

/**
 * @author Konstantin Bulenkov
 */
class FileStructurePopup(
  private val myProject: Project,
  private val myFileEditor: FileEditor,
  private val myModel: StructureUiModel,
) : Disposable, TreeActionsOwner, StructurePopup {
  private var myPopup: JBPopup? = null
  private var myTitle: @NlsContexts.PopupTitle String? = null

  val tree: Tree
  private val myTreeStructure: StructureViewTreeStructure
  private val myFilteringStructure: FilteringTreeStructure

  private val myAsyncTreeModel: AsyncTreeModel
  private val myStructureTreeModel: StructureTreeModel<*>
  private val mySpeedSearch: MyTreeSpeedSearch

  private val myCheckBoxes = hashMapOf<String, JBCheckBox>()
  private val myCheckBoxesPanel = Wrapper()
  private val myAutoClicked= mutableListOf<JBCheckBox>()
  private var myTestSearchFilter: String? = null
  private val myTriggeredCheckboxes = mutableListOf<Pair<String, JBCheckBox>>()
  private val myTreeExpander: TreeExpander
  private val myCopyPasteDelegator: CopyPasteDelegator
  private val mySorters = mutableListOf<AnAction>()

  private val cs = StructureViewScopeHolder.getInstance().cs.childScope("$this scope")

  private val constructorCallTime = System.nanoTime()
  private var showTime: Long = 0

  private var myCanClose = true
  var isDisposed: Boolean = false
    private set


  init {
    //Stop code analyzer to speed up the EDT
    DaemonCodeAnalyzer.getInstance(myProject).disableUpdateByTimer(this)

    myModel.setActionEnabled(StructureTreeAction.ALPHA_SORTER, true)

    myTreeStructure = object : StructureViewTreeStructure(myProject, myModel) {
      override fun rebuildTree() {
        if (!ApplicationManager.getApplication().isUnitTestMode() && myPopup!!.isDisposed()) {
          return
        }
        ProgressManager.getInstance().computePrioritized<Any?, RuntimeException?> {
          super.rebuildTree()
          myFilteringStructure.rebuild()
          null
        }
      }

      override fun isToBuildChildrenInBackground(element: Any): Boolean {
        return rootElement === element
      }

      @NonNls
      override fun toString(): @NonNls String {
        return "structure view tree structure(model=$myModel)"
      }
    }

    val filter = FileStructurePopupFilter()
    myFilteringStructure = FilteringTreeStructure(filter, myTreeStructure, false)

    myStructureTreeModel = StructureTreeModel<FilteringTreeStructure>(myFilteringStructure, this)
    myAsyncTreeModel = AsyncTreeModel(myStructureTreeModel, this)
    this.tree = MyTree(myAsyncTreeModel)
    tree.model.addTreeModelListener(SWExpandListener(this.tree, myModel))
    PopupUtil.applyNewUIBackground(this.tree)
    tree.getAccessibleContext().setAccessibleName(LangBundle.message("file.structure.tree.accessible.name"))

    myModel.addListener(object : StructureUiModelListener {
      override fun onTreeChanged() {
        rebuild(false)
      }

      override fun onActionsChanged() {
        updateActions()
      }
    })
    tree.setCellRenderer(NodeRenderer())
    myProject.getMessageBus().connect(this).subscribe<UISettingsListener>(UISettingsListener.TOPIC,
                                                                          UISettingsListener { rebuild(false) })

    tree.setTransferHandler(object : TransferHandler() {
      override fun importData(support: TransferSupport): Boolean {
        val s = CopyPasteManager.getInstance().getContents<String?>(DataFlavor.stringFlavor)
        if (s != null && !mySpeedSearch.isPopupActive) {
          mySpeedSearch.showPopup(s)
          return true
        }
        return false
      }
    })

    mySpeedSearch = MyTreeSpeedSearch()
    mySpeedSearch.setupListeners()
    mySpeedSearch.comparator = SpeedSearchComparator(false, true, " ()")

    myTreeExpander = DefaultTreeExpander(this.tree)
    myCopyPasteDelegator = CopyPasteDelegator(myProject, this.tree)

    TreeUtil.installActions(this.tree)
  }

  override fun show() {
    showWithResult()
  }

  fun showWithResult(): Promise<TreePath?> {
    val panel = createCenterPanel()
    tree.addTreeSelectionListener {
      if (myPopup!!.isVisible()) {
        val updateProcessor = myPopup!!.getUserData(PopupUpdateProcessor::class.java)
        if (updateProcessor != null) {
          val node = this.selectedNode
          updateProcessor.updatePopup(node)
        }
      }
    }

    myPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, this.tree)
      .setTitle(myTitle)
      .setResizable(true)
      .setModalContext(false)
      .setFocusable(true)
      .setRequestFocus(true)
      .setMovable(true)
      .setBelongsToGlobalPopupStack(true) //.setCancelOnClickOutside(false) //for debug and snapshots
      .setCancelOnOtherWindowOpen(true)
      .setCancelKeyEnabled(false)
      .setDimensionServiceKey(myProject,
                              dimensionServiceKey, true)
      .setCancelCallback {
        val listener = myProject.getMessageBus().syncPublisher<FileStructurePopupListener>(FileStructurePopupListener.TOPIC)
        listener.stateChanged(false)
        myCanClose
      }
      .setAdvertiser(SpeedSearchAdvertiser().addSpeedSearchAdvertisement())
      .createPopup()

    Disposer.register(myPopup!!, this)
    tree.emptyText.text = CommonBundle.getLoadingTreeNodeText()
    myPopup!!.showCenteredInCurrentWindow(myProject)

    IdeFocusManager.getInstance(myProject).requestFocus(this.tree, true)

    return rebuildAndSelect(false, null, null).onProcessed {
      UIUtil.invokeLaterIfNeeded {
        TreeUtil.ensureSelection(this.tree)
        myProject.service<FileStructurePopupLoadingStateUpdater>().installUpdater({ delayMillis -> this.installUpdater(delayMillis) }, myProject)
        showTime = System.nanoTime()
      }
    }
  }


  private fun installUpdater(delayMillis: Int) {
    if (ApplicationManager.getApplication().isUnitTestMode() || myPopup!!.isDisposed()) {
      return
    }
    val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, myPopup)
    alarm.addRequest(object : Runnable {
      var filter = ""

      override fun run() {
        alarm.cancelAllRequests()
        var prefix = mySpeedSearch.enteredPrefix
        tree.emptyText.text = if (StringUtil.isEmpty(prefix))
          LangBundle.message("status.text.structure.empty")
        else
          ("'$prefix' ${LangBundle.message("status.text.structure.empty.not.found")}")
        if (prefix == null) prefix = ""

        if (filter != prefix) {
          val isBackspace = prefix.length < filter.length
          filter = prefix
          rebuild(true).onProcessed {
            UIUtil.invokeLaterIfNeeded {
              if (isDisposed) return@invokeLaterIfNeeded
              TreeUtil.promiseExpandAll(tree)
              if (isBackspace && handleBackspace(filter)) {
                return@invokeLaterIfNeeded
              }
              if (myFilteringStructure.rootElement.getChildren().size == 0) {
                for (box in myCheckBoxes.values) {
                  if (!box.isSelected) {
                    myAutoClicked.add(box)
                    myTriggeredCheckboxes.addFirst(filter to box)
                    box.doClick()
                    filter = ""
                    break
                  }
                }
              }
            }
          }
        }
        if (!alarm.isDisposed) {
          alarm.addRequest(this, delayMillis)
        }
      }
    }, delayMillis)
  }

  private fun handleBackspace(filter: String): Boolean {
    var clicked = false
    val iterator = myTriggeredCheckboxes.iterator()
    while (iterator.hasNext()) {
      val next = iterator.next()
      if (next.first.length < filter.length) break

      iterator.remove()
      next.second.doClick()
      clicked = true
    }
    return clicked
  }

  fun select(element: StructureUiTreeElement): Promise<TreePath?> {
    //todo check no backend logic is lost
    val visitor: TreeVisitor = object : TreeVisitor {
      override fun visitThread() =  VisitThread.BGT

      override fun visit(path: TreePath): TreeVisitor.Action {
        val last = path.lastPathComponent
        val value = unwrapTreeElement(last)?.value

        return if (value == element) {
          TreeVisitor.Action.INTERRUPT
        }
        else {
          TreeVisitor.Action.CONTINUE
        }
      }
    }

    return selectInner(visitor)
  }

  fun select(element: StructureViewTreeElement): Promise<TreePath?> {
    //todo check no backend logic is lost
    val visitor: TreeVisitor = object : TreeVisitor {
      override fun visitThread() =  VisitThread.BGT

      override fun visit(path: TreePath): TreeVisitor.Action {
        val last = path.lastPathComponent
        val treeElement = unwrapTreeElement(last)

        return if (treeElement == element) {
          TreeVisitor.Action.INTERRUPT
        }
        else if (treeElement != null && !treeElement.isAncestorOf(element)) {
          TreeVisitor.Action.SKIP_CHILDREN
        }
        else {
          TreeVisitor.Action.CONTINUE
        }
      }

      private fun StructureViewTreeElement.isAncestorOf(child: StructureViewTreeElement): Boolean {
        var element = child.parent
        while (element != null) {
          if (element == this) return true
          element = element.parent
        }
        return false
      }
    }

    return selectInner(visitor)
  }

  private fun selectInner(visitor: TreeVisitor): Promise<TreePath?> {
    return myAsyncTreeModel
      .accept(visitor)
      .thenAsync<TreePath?> {
        if (it == null) return@thenAsync rejectedPromise()

        tree.expandPath(it)
        TreeUtil.selectPath(tree, it)
        TreeUtil.ensureSelection(tree)
        return@thenAsync resolvedPromise(it)
      }
  }

  @TestOnly
  fun rebuildAndUpdate(): AsyncPromise<Void?> {
    val result = AsyncPromise<Void?>()
    val visitor = TreeVisitor {
      val node = TreeUtil.getLastUserObject(AbstractTreeNode::class.java, it)
      node?.update()
      TreeVisitor.Action.CONTINUE
    }
    rebuild(false).onProcessed {
      myAsyncTreeModel.accept(visitor).onProcessed { result.setResult(null) }
    }
    return result
  }

  override fun dispose() {
    this.isDisposed = true
    if (showTime != 0L) {
      FileStructurePopupTimeTracker.logShowTime(System.nanoTime() - showTime)
    }
    FileStructurePopupTimeTracker.logPopupLifeTime(System.nanoTime() - constructorCallTime)
  }

  fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout())
    panel.preferredSize = JBUI.size(540, 500)

    val F4 = ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE).shortcutSet.getShortcuts()
    val ENTER = CustomShortcutSet.fromString("ENTER").shortcuts
    val shortcutSet = CustomShortcutSet(*(F4 + ENTER))
    NavigateSelectedElementAction(panel).registerCustomShortcutSet(shortcutSet, panel)

    DumbAwareAction.create {
      if (mySpeedSearch.isPopupActive) {
        mySpeedSearch.hidePopup()
      }
      else {
        myPopup!!.cancel()
      }
    }.registerCustomShortcutSet(CustomShortcutSet.fromString("ESCAPE"), this.tree)
    object : ClickListener() {
      override fun onClick(e: MouseEvent, clickCount: Int): Boolean {
        val path = tree.getClosestPathForLocation(e.getX(), e.getY())
        val bounds = if (path == null) null else tree.getPathBounds(path)
        if (bounds == null || bounds.x > e.getX() || bounds.y > e.getY() || bounds.y + bounds.height < e.getY()) return false
        navigateSelectedElement()
        return true
      }
    }.installOn(this.tree)


    val topPanel = JPanel(BorderLayout())

    val rightToolbarPanel = JPanel(BorderLayout())
    rightToolbarPanel.setBackground(JBUI.CurrentTheme.Popup.toolbarPanelColor())
    rightToolbarPanel.setBorder(JBUI.Borders.empty())

    rightToolbarPanel.add(createUpdatePendingIndicator(myModel), BorderLayout.WEST)

    rightToolbarPanel.add(createSettingsButton(), BorderLayout.EAST)

    topPanel.add(rightToolbarPanel, BorderLayout.EAST)
    topPanel.add(myCheckBoxesPanel, BorderLayout.WEST)

    updateActions()

    topPanel.setBackground(JBUI.CurrentTheme.Popup.toolbarPanelColor())
    topPanel.setBorder(JBUI.Borders.emptyLeft(UIUtil.DEFAULT_HGAP))

    panel.add(topPanel, BorderLayout.NORTH)
    val scrollPane = ScrollPaneFactory.createScrollPane(this.tree)
    scrollPane.setBorder(IdeBorderFactory.createBorder(JBUI.CurrentTheme.Popup.toolbarBorderColor(), SideBorder.TOP or SideBorder.BOTTOM))
    panel.add(scrollPane, BorderLayout.CENTER)
    panel.addFocusListener(object : FocusAdapter() {
      override fun focusLost(e: FocusEvent?) {
        myPopup!!.cancel()
      }
    })

    return wrapComponent(panel, UiDataProvider { uiDataSnapshot(it) })
  }

  private fun createUpdatePendingIndicator(treeModel: StructureUiModel): JComponent {
    val component = JLabel(SpinningProgressIcon())
    component.isVisible = false

    cs.launch(start = CoroutineStart.UNDISPATCHED) {
      treeModel.getUpdatePendingFlow().collect {
        component.isVisible = it
      }
    }

    return component
  }

  private fun updateActions() {
    val actions = myModel.getActions()
    val sorters = actions.filter { it.actionType == StructureTreeAction.Type.SORTER }
    val checkboxActions = actions.filterIsInstance<CheckboxTreeAction>()

    mySorters.clear()
    mySorters.addAll(sorters.map { MyStructureTreeAction(it, myModel) })

    myAutoClicked.clear()
    myTriggeredCheckboxes.clear()


    val checkBoxCount = checkboxActions.size

    val cols = if (checkBoxCount > 0 && checkBoxCount % 4 == 0) checkBoxCount / 2 else 3
    val singleRow = checkBoxCount <= cols

    val chkPanel = JPanel(GridLayout(0, cols, scale(UIUtil.DEFAULT_HGAP), 0))
    chkPanel.setOpaque(false)

    for (filter in checkboxActions) {
      addCheckbox(chkPanel, filter)
    }

    chkPanel.addHierarchyListener(
      HierarchyListener { event ->
        if ((event.getChangeFlags() and HierarchyEvent.PARENT_CHANGED.toLong()) != 0L && event.getChanged() === chkPanel) {
          val topPanel = myCheckBoxesPanel.getParent()
          val prefSize = topPanel.preferredSize
          if (singleRow) {
            prefSize.height = JBUI.CurrentTheme.Popup.toolbarHeight()
          }
          topPanel.setPreferredSize(prefSize)
        }
      })
    myCheckBoxesPanel.setContent(chkPanel)
  }

  private class MyStructureTreeAction(action: StructureTreeAction, model: StructureUiModel) : StructureTreeActionWrapper(action, model) {
    init {
      model.setActionEnabled(action, getDefaultValue(action))
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      super.setSelected(e, state)
      saveState(myAction, state)
    }
  }

  private fun uiDataSnapshot(sink: DataSink) {
    sink.set<Project>(CommonDataKeys.PROJECT, myProject)
    sink.set<FileEditor>(PlatformCoreDataKeys.FILE_EDITOR, myFileEditor)
    if (myFileEditor is TextEditor) {
      sink.set<Editor>(OpenFileDescriptor.NAVIGATE_IN_EDITOR, myFileEditor.getEditor())
    }
    sink.set<JBPopup>(LangDataKeys.POSITION_ADJUSTER_POPUP, myPopup)
    sink.set<CopyProvider>(PlatformDataKeys.COPY_PROVIDER, myCopyPasteDelegator.copyProvider)
    sink.set<TreeExpander>(PlatformDataKeys.TREE_EXPANDER, myTreeExpander)

    val selection = tree.getSelectionPaths()
    selection?.mapNotNull { unwrapTreeElement(it.lastPathComponent) }
    val selectedElements = selection?.mapNotNull { unwrapTreeElement(it.lastPathComponent) }
    sink.lazy<Navigatable>(CommonDataKeys.NAVIGATABLE) { selectedElements?.first() }
    sink.lazy<Array<Navigatable?>>(CommonDataKeys.NAVIGATABLE_ARRAY) { selectedElements?.nullize()?.toTypedArray() }
  }

  private fun createSettingsButton(): JComponent {
    val label = JLabel(AllIcons.General.GearPlain)
    label.setBorder(JBUI.Borders.empty(0, 4))
    label.setHorizontalAlignment(SwingConstants.RIGHT)
    label.setVerticalAlignment(SwingConstants.CENTER)
    label.getAccessibleContext().setAccessibleName(LangBundle.message("file.structure.settings.accessible.name"))

    object : ClickListener() {
      override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
        val group = DefaultActionGroup()
        if (!mySorters.isEmpty()) {
          group.addAll(mySorters)
          group.addSeparator()
        }

        //addGroupers(group);
        //addFilters(group);
        group.add(ToggleNarrowDownAction())

        val dataManager = DataManager.getInstance()
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
          null, group, dataManager.getDataContext(label), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
        popup.addListener(object : JBPopupListener {
          override fun onClosed(event: LightweightWindowEvent) {
            myCanClose = true
          }
        })
        myCanClose = false
        popup.showUnderneathOf(label)
        return true
      }
    }.installOn(label)
    return label
  }

  private val selectedNode: StructureViewTreeElement?
    get() {
      val path = tree.selectionPath
      return unwrapTreeElement(if (path == null) null else path.lastPathComponent)
    }

  private fun navigateSelectedElement(): Boolean {
    val selectedNode = this.selectedNode
    if (ApplicationManager.getApplication().isInternal()) {
      val enteredPrefix = mySpeedSearch.enteredPrefix
      val itemText: String? = if (selectedNode != null) getSpeedSearchText(selectedNode) else null
      if (StringUtil.isNotEmpty(enteredPrefix) && StringUtil.isNotEmpty(itemText)) {
        LOG.info("Chosen in file structure popup by prefix '$enteredPrefix': '$itemText'")
      }
    }

    val succeeded = Ref<Boolean>()
    val commandProcessor = CommandProcessor.getInstance()
    commandProcessor.executeCommand(myProject, Runnable {
      if (selectedNode != null) {
        if (selectedNode.canNavigateToSource()) {
          selectedNode.navigate(true)
          myPopup!!.cancel()
          succeeded.set(true)
        }
        else {
          succeeded.set(false)
        }
      }
      else {
        succeeded.set(false)
      }
      IdeDocumentHistory.getInstance(myProject).includeCurrentCommandAsNavigation()
    }, LangBundle.message("command.name.navigate"), null)
    return succeeded.get()
  }

  private fun addCheckbox(panel: JPanel, action: CheckboxTreeAction) {
    var text = action.checkboxText

    val shortcuts = extractShortcutFor(action)


    val checkBox = JBCheckBox()
    checkBox.setOpaque(false)
    UIUtil.applyStyle(UIUtil.ComponentStyle.SMALL, checkBox)

    val selected = getDefaultValue(action)
    checkBox.setSelected(selected)
    val isRevertedStructureFilter = action.isReverted
    myModel.setActionEnabled(action, isRevertedStructureFilter != selected)
    checkBox.addActionListener {
      logFileStructureCheckboxClick(action)
      val state = checkBox.isSelected
      if (!myAutoClicked.contains(checkBox)) {
        saveState(action, state)
      }
      myModel.setActionEnabled(action, isRevertedStructureFilter != state)
      rebuild(false).onProcessed {
        if (mySpeedSearch.isPopupActive) {
          mySpeedSearch.refreshSelection()
        }
      }
    }
    checkBox.setFocusable(false)

    if (shortcuts.isNotEmpty()) {
      text += " (" + KeymapUtil.getShortcutText(shortcuts[0]) + ")"
      DumbAwareAction.create {
        checkBox.doClick()
      }.registerCustomShortcutSet(CustomShortcutSet(*shortcuts), this.tree)
    }
    checkBox.setText(text)
    panel.add(checkBox)

    myCheckBoxes[action.name] = checkBox
  }

  private fun logFileStructureCheckboxClick(action: StructureTreeAction) {
    val fileType = myFileEditor.getFile().fileType
    val language = if (fileType is LanguageFileType) fileType.language else null

    ActionsCollectorImpl.recordActionInvoked(myProject) {
      add(EventFields.ActionPlace.with(ActionPlaces.FILE_STRUCTURE_POPUP))
      add(EventFields.CurrentFile.with(language))
      add(ActionsEventLogGroup.ACTION_CLASS.with(action.javaClass))
      add(ActionsEventLogGroup.ACTION_ID.with(action.name))
    }
  }

  private fun rebuild(refilterOnly: Boolean): Promise<Void?> {
    val selection = tree.getSelectionPaths()?.firstNotNullOf { unwrapTreeElement(it.lastPathComponent) } ?: myModel.editorSelection.value
    return rebuildAndSelect(refilterOnly, selection, null).then<Void?>(Function { null })
  }

  private fun rebuildAndSelect(refilterOnly: Boolean, selection: Any?, rebuildStartTime: Long?): Promise<TreePath?> {
    var rebuildStartTime = rebuildStartTime
    if (rebuildStartTime == null) {
      rebuildStartTime = System.nanoTime()
    }

    val result = AsyncPromise<TreePath?>()
    val finalLastRebuildStartTime = rebuildStartTime
    myStructureTreeModel.invoker.invoke(Runnable {
      if (refilterOnly) {
        myFilteringStructure.rebuild()
        myFilteringStructure.refilter()
        myStructureTreeModel.invalidateAsync().thenRun {
          val promise = when (selection) {
            is StructureViewTreeElement -> select(selection)
            is StructureUiTreeElement -> select(selection)
            else -> myAsyncTreeModel.accept { TreeVisitor.Action.CONTINUE }
          }
          promise.onError {
            result.setError("rejected")
            mySpeedSearch.refreshSelection() // Selection failed, let the speed search reflect that by coloring itself red.
          }.onSuccess {
            EdtInvocationManager.invokeLaterIfNeeded {
              //maybe readaction
              WriteIntentReadAction.run {
                TreeUtil.expand(this.tree, myModel.minimumAutoExpandDepth)
                TreeUtil.ensureSelection(this.tree)
                mySpeedSearch.refreshSelection()
                result.setResult(it)
                FileStructurePopupTimeTracker.logRebuildTime(System.nanoTime() - finalLastRebuildStartTime)
              }
            }
          }
        }
      }
      else {
        myTreeStructure.rebuildTree()
        myStructureTreeModel.invalidateAsync().thenRun {
          rebuildAndSelect(true, selection ?: myModel.editorSelection.value, finalLastRebuildStartTime)
            .processed(result)
        }
      }
    }).onError {
      result.setError(it)
    }
    return result
  }

  override fun setTitle(title: @NlsContexts.PopupTitle String) {
    myTitle = title
  }

  @get:TestOnly val speedSearch: TreeSpeedSearch
    get() = mySpeedSearch

  @TestOnly
  fun setSearchFilterForTests(filter: String?) {
    myTestSearchFilter = filter
  }

  @TestOnly
  fun setTreeActionState(action: StructureTreeAction, state: Boolean) {
    val checkBox = myCheckBoxes[action.name]
    if (checkBox != null) {
      checkBox.setSelected(state)
      for (listener in checkBox.actionListeners) {
        listener.actionPerformed(ActionEvent(this, 1, ""))
      }
    }
  }

  override fun setActionActive(name: String?, state: Boolean) {
  }

  override fun isActionActive(name: String?): Boolean {
    return false
  }

  private inner class FileStructurePopupFilter : ElementFilter<Any?> {
    private var myLastFilter: String? = null
    private val isUnitTest = ApplicationManager.getApplication().isUnitTestMode()

    override fun shouldBeShowing(value: Any?): Boolean {
      if (!isShouldNarrowDown) return true

      val filter: String? = searchPrefix
      if (!StringUtil.equals(myLastFilter, filter)) {
        myLastFilter = filter
      }
      if (filter != null) {
        val text: String? = if (value is StructureViewTreeElement) getSpeedSearchText(value) else null
        if (text == null) return false

        return matches(filter, text)
      }
      return true
    }

    fun matches(filter: String, text: String): Boolean {
      return (isUnitTest || mySpeedSearch.isPopupActive) &&
             StringUtil.isNotEmpty(filter) && mySpeedSearch.comparator.matchingFragments(filter, text) != null
    }
  }

  private val searchPrefix: String?
    get() {
      if (ApplicationManager.getApplication().isUnitTestMode()) return myTestSearchFilter

      return if (!StringUtil.isEmpty(mySpeedSearch.enteredPrefix)) mySpeedSearch.enteredPrefix else null
    }

  private inner class MyTreeSpeedSearch : TreeSpeedSearch(this.tree, true, null, java.util.function.Function { path: TreePath? ->
    val element = unwrapTreeElement(path)
    if (element != null) getSpeedSearchText(element) else null
  }) {
    @Volatile
    private var myPopupVisible = false

    override fun showPopup(searchText: String?) {
      super.showPopup(searchText)
      myPopupVisible = true
    }

    override fun hidePopup() {
      super.hidePopup()
      myPopupVisible = false
    }

    override fun isPopupActive(): Boolean {
      return myPopupVisible
    }

    override fun getComponentLocationOnScreen(): Point? {
      return myPopup!!.getContent().locationOnScreen
    }

    override fun getComponentVisibleRect(): Rectangle? {
      return myPopup!!.getContent().getVisibleRect()
    }

    override fun findElement(s: String): Any? {
      val elements = SpeedSearchObjectWithWeight.findElement(s, this)
      val best = elements.firstOrNull()
      if (best == null) return null
      val initial = myModel.editorSelection.value
      if (initial != null) {
        // find children of the initial element
        val bestForParent: SpeedSearchObjectWithWeight? = find(initial, elements) { parent: StructureUiTreeElement, path: TreePath? ->
          isParent(parent, path)
        }
        if (bestForParent != null) return bestForParent.node
        // find siblings of the initial element
        val parent = initial.parent
        if (parent != null) {
          val bestSibling: SpeedSearchObjectWithWeight? = find(parent, elements) { parent: StructureUiTreeElement, path: TreePath? ->
            isParent(parent, path)
          }
          if (bestSibling != null) return bestSibling.node
        }
        // find grand children of the initial element
        val bestForAncestor: SpeedSearchObjectWithWeight? = find(initial, elements) { ancestor: StructureUiTreeElement, path: TreePath? ->
          isAncestor(ancestor, path)
        }
        if (bestForAncestor != null) return bestForAncestor.node
      }
      return best.node
    }
  }

  internal class MyTree(treeModel: TreeModel?) : DnDAwareTree(treeModel), PlaceProvider {
    init {
      setRootVisible(false)
      setShowsRootHandles(true)
    }

    override fun getPlace(): String {
      return ActionPlaces.STRUCTURE_VIEW_POPUP
    }
  }

  private inner class NavigateSelectedElementAction(private val myPanel: JPanel) : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
      val succeeded = navigateSelectedElement()
      if (succeeded) {
        unregisterCustomShortcutSet(myPanel)
      }
    }
  }

  private fun unwrapTreeElement(o: Any?): StructureViewTreeElement? {
    val p = TreeUtil.getUserObject(o)
    val node = if (p is FilteringNode) p.delegate else p
    return node as? StructureViewTreeElement
  }

  private fun unwrapTreeElement(o: TreePath?): StructureViewTreeElement? {
    val p = TreeUtil.getLastUserObject(o)
    val node = if (p is FilteringNode) p.delegate else p
    return node as? StructureViewTreeElement
  }

  private inner class ToggleNarrowDownAction : ToggleAction(IdeBundle.message("checkbox.narrow.down.on.typing")) {
    override fun isSelected(e: AnActionEvent): Boolean {
      return isShouldNarrowDown
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
      return ActionUpdateThread.BGT
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      PropertiesComponent.getInstance().setValue(NARROW_DOWN_PROPERTY_KEY, state.toString())
      if (mySpeedSearch.isPopupActive && !StringUtil.isEmpty(mySpeedSearch.enteredPrefix)) {
        rebuild(true)
      }
    }
  }

  companion object {
    private val LOG = Logger.getInstance(FileStructurePopup::class.java)

    @NonNls
    private const val NARROW_DOWN_PROPERTY_KEY: @NonNls String = "FileStructurePopup.narrowDown"

    private val isShouldNarrowDown: Boolean
      get() = PropertiesComponent.getInstance().getBoolean(
        NARROW_DOWN_PROPERTY_KEY, true)

    @get:NonNls private val dimensionServiceKey: @NonNls String
      get() = "StructurePopup"

    fun extractShortcutFor(action: CheckboxTreeAction): Array<Shortcut> {
      if (action.actionIdForShortcut != null) {
        return KeymapUtil.getActiveKeymapShortcuts(action.actionIdForShortcut).getShortcuts()
      }
      return action.shortcuts ?: arrayOf()
    }

    private fun getDefaultValue(action: StructureTreeAction): Boolean {
      val propertyName = action.getPropertyName()
      val defaultValue = action.isEnabledByDefault
      return PropertiesComponent.getInstance().getBoolean(TreeStructureUtil.getPropertyName(propertyName), defaultValue)
    }

    private fun saveState(action: StructureTreeAction, state: Boolean) {
      val propertyName = action.getPropertyName()
      val defaultValue = action.isEnabledByDefault
      PropertiesComponent.getInstance().setValue(TreeStructureUtil.getPropertyName(propertyName), state, defaultValue)
    }

    fun getSpeedSearchText(element: StructureViewTreeElement): String? {
      return element.getValue().speedSearchText
    }

    private fun find(
      element: StructureUiTreeElement,
      objects: MutableList<out SpeedSearchObjectWithWeight>,
      predicate: (StructureUiTreeElement, TreePath?) -> Boolean,
    ): SpeedSearchObjectWithWeight? {
      return objects.find { predicate(element, (it.node as? TreePath)) }
    }

    private fun isElement(element: StructureUiTreeElement, path: TreePath?): Boolean {
      val treeElement = TreeUtil.getLastUserObject(FilteringNode::class.java, path)!!.delegate as StructureViewTreeElement
      return element == treeElement.getValue()
    }

    private fun isParent(parent: StructureUiTreeElement, path: TreePath?): Boolean {
      return path != null && isElement(parent, path.parentPath)
    }

    private fun isAncestor(ancestor: StructureUiTreeElement, path: TreePath?): Boolean {
      var path = path
      while (path != null) {
        if (isElement(ancestor, path)) return true
        path = path.parentPath
      }
      return false
    }
  }
}