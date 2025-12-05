// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.frontend

import com.intellij.CommonBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.ide.*
import com.intellij.ide.dnd.aware.DnDAwareTree
import com.intellij.ide.structureView.ModelListener
import com.intellij.ide.structureView.SearchableTextProvider
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.structureView.newStructureView.*
import com.intellij.ide.ui.UISettingsListener
import com.intellij.ide.util.*
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.ide.util.treeView.smartTree.*
import com.intellij.internal.statistic.collectors.fus.actions.persistence.ActionsCollectorImpl
import com.intellij.internal.statistic.collectors.fus.actions.persistence.ActionsEventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.lang.LangBundle
import com.intellij.navigation.LocationPresentation
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.UiDataProvider.Companion.wrapComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.CommandProcessor
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
import com.intellij.openapi.util.*
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.util.PsiUtilCore
import com.intellij.ui.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.popup.AbstractPopup
import com.intellij.ui.popup.HintUpdateSupply
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
import com.intellij.util.*
import com.intellij.util.containers.JBIterable
import com.intellij.util.containers.toArray
import com.intellij.util.text.TextRangeUtil
import com.intellij.util.ui.*
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.annotations.ApiStatus
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
import java.awt.datatransfer.Transferable
import java.awt.event.*
import java.util.function.BiPredicate
import java.util.function.Consumer
import javax.swing.*
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath
import kotlin.concurrent.Volatile

/**
 * @author Konstantin Bulenkov
 */
class FileStructurePopup @ApiStatus.Internal constructor(
  private val myProject: Project,
  private val myFileEditor: FileEditor,
  private val myTreeModel: StructureViewModel,
  private val myCallbackAfterNavigation: Consumer<AbstractTreeNode<*>?>?,
) : Disposable, TreeActionsOwner, StructurePopup {
  private val myTreeModelWrapper: StructureViewModel
  private val myTreeActionsOwner: TreeStructureActionsOwner

  private var myPopup: JBPopup? = null
  private var myTitle: @NlsContexts.PopupTitle String? = null

  val tree: Tree
  private val myTreeStructure: SmartTreeStructure
  private val myFilteringStructure: FilteringTreeStructure

  private val myAsyncTreeModel: AsyncTreeModel
  private val myStructureTreeModel: StructureTreeModel<*>
  private val mySpeedSearch: MyTreeSpeedSearch?

  private val myInitialElement: Any?
  private val myCheckBoxes = mutableMapOf<String, JBCheckBox>()
  private val myAutoClicked = mutableListOf<JBCheckBox>()
  private var myTestSearchFilter: String? = null
  private val myTriggeredCheckboxes = mutableListOf<Pair<String, JBCheckBox>>()
  private val myTreeExpander: TreeExpander
  private val myCopyPasteDelegator: CopyPasteDelegator

  private val constructorCallTime = System.nanoTime()
  private var showTime: Long = 0

  private var myCanClose = true
  var isDisposed: Boolean = false
    private set

  constructor(
    project: Project,
    fileEditor: FileEditor,
    treeModel: StructureViewModel,
  ) : this(project, fileEditor, treeModel, null)

  init {
    //Stop code analyzer to speed up the EDT
    DaemonCodeAnalyzer.getInstance(myProject).disableUpdateByTimer(this)

    myTreeActionsOwner = TreeStructureActionsOwner(myTreeModel)
    myTreeActionsOwner.setActionIncluded(Sorter.ALPHA_SORTER, true)
    myTreeModelWrapper = TreeModelWrapper(myTreeModel, myTreeActionsOwner)
    Disposer.register(this, myTreeModelWrapper)

    myTreeStructure = object : SmartTreeStructure(myProject, myTreeModelWrapper) {
      override fun rebuildTree() {
        if (!ApplicationManager.getApplication().isUnitTestMode() && myPopup!!.isDisposed()) {
          return
        }
        ProgressManager.getInstance().computePrioritized<Any?, RuntimeException?>(ThrowableComputable {
          super.rebuildTree()
          myFilteringStructure.rebuild()
          null
        })
      }

      override fun isToBuildChildrenInBackground(element: Any): Boolean {
        return getRootElement() === element
      }

      override fun createTree(): TreeElementWrapper {
        return StructureViewComponent.createWrapper(myProject, myModel.getRoot(), myModel)
      }

      @NonNls
      override fun toString(): @NonNls String {
        return "structure view tree structure(model=$myTreeModelWrapper)"
      }
    }

    val filter = FileStructurePopupFilter()
    myFilteringStructure = FilteringTreeStructure(filter, myTreeStructure, false)

    myStructureTreeModel = StructureTreeModel<FilteringTreeStructure>(myFilteringStructure, this)
    myAsyncTreeModel = AsyncTreeModel(myStructureTreeModel, this)
    this.tree = MyTree(myAsyncTreeModel)
    StructureViewComponent.registerAutoExpandListener(this.tree, myTreeModel)
    PopupUtil.applyNewUIBackground(this.tree)
    tree.accessibleContext.accessibleName = LangBundle.message("file.structure.tree.accessible.name")

    val modelListener = ModelListener { rebuild(false) }
    myTreeModel.addModelListener(modelListener)
    Disposer.register(this, Disposable { myTreeModel.removeModelListener(modelListener) })
    tree.setCellRenderer(NodeRenderer())
    myProject.getMessageBus().connect(this)
      .subscribe<UISettingsListener>(UISettingsListener.TOPIC, UISettingsListener { rebuild(false) })

    tree.setTransferHandler(object : TransferHandler() {
      override fun importData(support: TransferSupport): Boolean {
        val s = CopyPasteManager.getInstance().getContents<String?>(DataFlavor.stringFlavor)
        if (s != null && !mySpeedSearch!!.isPopupActive) {
          mySpeedSearch.showPopup(s)
          return true
        }
        return false
      }

      override fun createTransferable(component: JComponent?): Transferable? {
        val pairs = JBIterable.of(*tree.getSelectionPaths())
          .filterMap { path -> TreeUtil.getLastUserObject(path) }
          .filter(FilteringNode::class.java)
          .filterMap { o ->
            if (o.delegate is PsiElement) Pair(o, o.delegate as PsiElement)
            else null
          }
          .collect()
        if (pairs.isEmpty()) return null
        val psiSelection = pairs.map { it.second }.toSet()

        val text = StringUtil.join(pairs.map { pair ->
          val psi = pair.second
          val defaultPresentation = pair.first.presentation.presentableText
          if (psi == null) return@map defaultPresentation
          var p = psi.parent
          while (p != null) {
            if (psiSelection.contains(p)) return@map null
            p = p.parent
          }
          ObjectUtils.chooseNotNull(psi.text, defaultPresentation)
        }.filterNotNull(), "\n")

        val htmlText = "<body>\n$text\n</body>"
        return TextTransferable(XmlStringUtil.wrapInHtml(htmlText), text)
      }

      override fun getSourceActions(component: JComponent?): Int {
        return COPY
      }
    })

    mySpeedSearch = MyTreeSpeedSearch()
    mySpeedSearch.setupListeners()
    mySpeedSearch.comparator = SpeedSearchComparator(false, true, " ()")

    myTreeExpander = DefaultTreeExpander(this.tree)
    myCopyPasteDelegator = CopyPasteDelegator(myProject, this.tree)

    myInitialElement = myTreeModel.getCurrentEditorElement()
    TreeUtil.installActions(this.tree)
  }

  override fun show() {
    showWithResult()
  }

  fun showWithResult(): Promise<TreePath?> {
    val panel = createCenterPanel()
    tree.addTreeSelectionListener(TreeSelectionListener { _ ->
      if (myPopup!!.isVisible) {
        val updateProcessor = myPopup!!.getUserData(PopupUpdateProcessor::class.java)
        if (updateProcessor != null) {
          val node: AbstractTreeNode<*>? = this.selectedNode
          updateProcessor.updatePopup(node)
        }
      }
    })

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
      .setCancelCallback(Computable {
        val listener =
          myProject.messageBus.syncPublisher(FileStructurePopupListener.TOPIC)
        listener.stateChanged(false)
        myCanClose
      })
      .setAdvertiser(SpeedSearchAdvertiser().addSpeedSearchAdvertisement())
      .createPopup()

    Disposer.register(myPopup!!, this)
    tree.emptyText.text = CommonBundle.getLoadingTreeNodeText()
    myPopup!!.showCenteredInCurrentWindow(myProject)

    (myPopup as AbstractPopup).setShowHints(true)

    IdeFocusManager.getInstance(myProject).requestFocus(tree, true)

    return rebuildAndSelect(false, myInitialElement, null).onProcessed {
      UIUtil.invokeLaterIfNeeded(Runnable {
        TreeUtil.ensureSelection(tree)
        myProject.getService(FileStructurePopupLoadingStateUpdater::class.java).installUpdater(
          Consumer { delayMillis: Int? -> this.installUpdater(delayMillis!!) }, myProject, myTreeModel)
        showTime = System.nanoTime()
      })
    }
  }


  private fun installUpdater(delayMillis: Int) {
    if (ApplicationManager.getApplication().isUnitTestMode || myPopup!!.isDisposed) {
      return
    }
    val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, myPopup)
    alarm.addRequest(object : Runnable {
      var filter: String? = ""

      override fun run() {
        alarm.cancelAllRequests()
        var prefix = mySpeedSearch!!.enteredPrefix
        tree.emptyText.text = if (StringUtil.isEmpty(prefix))
                                      LangBundle.message("status.text.structure.empty")
                                    else
                                      "'$prefix' " + LangBundle.message("status.text.structure.empty.not.found")
        if (prefix == null) prefix = ""

        if (filter != prefix) {
          val isBackspace = prefix.length < filter!!.length
          filter = prefix
          rebuild(true).onProcessed {
            UIUtil.invokeLaterIfNeeded {
              if (isDisposed) return@invokeLaterIfNeeded
              TreeUtil.promiseExpandAll(tree)
              if (isBackspace && handleBackspace(filter!!)) {
                return@invokeLaterIfNeeded
              }
              if (myFilteringStructure.rootElement.getChildren().size == 0) {
                for (box in myCheckBoxes.values) {
                  if (!box.isSelected) {
                    myAutoClicked.add(box)
                    myTriggeredCheckboxes.add(0, Pair(filter!!, box))
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

  fun select(element: Any?): Promise<TreePath?> {
    val editorOffset: Int
    if (myFileEditor is TextEditor) {
      editorOffset = myFileEditor.getEditor().getCaretModel().offset
    }
    else {
      editorOffset = -1
    }
    val state = StructureViewSelectVisitorState()
    val visitor: TreeVisitor = object : TreeVisitor {
      override fun visitThread(): VisitThread {
        return VisitThread.BGT
      }

      override fun visit(path: TreePath): TreeVisitor.Action {
        return StructureViewComponent.visitPathForElementSelection(path, element, editorOffset, state)
      }
    }
    val action = Function { path: TreePath? ->
      tree.expandPath(path)
      TreeUtil.selectPath(tree, path)
      TreeUtil.ensureSelection(tree)
      resolvedPromise(path)
    }
    val fallback: Function<TreePath?, Promise<TreePath?>?> = object : Function<TreePath?, Promise<TreePath?>?> {
      override fun `fun`(path: TreePath?): Promise<TreePath?>? {
        if (path == null && state.isOptimizationUsed()) {
          // Some structure views merge unrelated psi elements into a structure node (MarkdownStructureViewModel).
          // So turn off the isAncestor() optimization and retry once.
          state.disableOptimization()
          return myAsyncTreeModel.accept(visitor).thenAsync<TreePath?>(this)
        }
        else {
          var adjusted = path ?: state.bestMatch
          if (path == null && adjusted != null && !state.isExactMatch && element is PsiElement) {
            val minChild = findClosestPsiElement(element, adjusted, myAsyncTreeModel)
            if (minChild != null) adjusted = adjusted.pathByAddingChild(minChild)
          }
          return if (adjusted == null) rejectedPromise() else action.`fun`(adjusted)
        }
      }
    }

    return myAsyncTreeModel
      .accept(visitor)
      .thenAsync<TreePath?>(fallback)
  }

  @TestOnly
  fun rebuildAndUpdate(): AsyncPromise<Void?> {
    val result = AsyncPromise<Void?>()
    val visitor = TreeVisitor { path: TreePath? ->
      val node = TreeUtil.getLastUserObject(AbstractTreeNode::class.java, path)
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

  fun getCurrentElement(psiFile: PsiFile?): PsiElement? {
    PsiDocumentManager.getInstance(myProject).commitAllDocuments()

    val elementAtCursor = myTreeModelWrapper.getCurrentEditorElement()
    if (elementAtCursor is PsiElement) {
      return elementAtCursor
    }

    if (psiFile != null && myFileEditor is TextEditor) {
      return psiFile.getViewProvider().findElementAt(myFileEditor.getEditor().getCaretModel().offset)
    }

    return null
  }

  fun createCenterPanel(): JComponent {
    val fileStructureFilters = mutableListOf<FileStructureFilter>()
    val fileStructureNodeProviders = mutableListOf<FileStructureNodeProvider<*>>()
    run {
      for (filter in myTreeModel.getFilters()) {
        if (filter is FileStructureFilter) {
          myTreeActionsOwner.setActionIncluded(filter, true)
          fileStructureFilters.add(filter)
        }
      }

      if (myTreeModel is ProvidingTreeModel) {
        for (provider in (myTreeModel as ProvidingTreeModel).getNodeProviders()) {
          if (provider is FileStructureNodeProvider<*>) {
            fileStructureNodeProviders.add(provider)
          }
        }
      }
    }

    val checkBoxCount = fileStructureNodeProviders.size + fileStructureFilters.size
    val panel = JPanel(BorderLayout())
    panel.preferredSize = JBUI.size(540, 500)
    val cols = if (checkBoxCount > 0 && checkBoxCount % 4 == 0) checkBoxCount / 2 else 3
    val singleRow = checkBoxCount <= cols
    val chkPanel = JPanel(GridLayout(0, cols,
                                     scale(UIUtil.DEFAULT_HGAP), 0))
    chkPanel.setOpaque(false)

    val f4 = ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE).shortcutSet.shortcuts
    val enter = CustomShortcutSet.fromString("ENTER").shortcuts
    val shortcutSet = CustomShortcutSet(*ArrayUtil.mergeArrays(f4, enter))
    NavigateSelectedElementAction(panel).registerCustomShortcutSet(shortcutSet, panel)

    DumbAwareAction.create {
      if (mySpeedSearch != null && mySpeedSearch.isPopupActive) {
        mySpeedSearch.hidePopup()
      }
      else {
        myPopup!!.cancel()
      }
    }.registerCustomShortcutSet(CustomShortcutSet.fromString("ESCAPE"), tree)
    object : ClickListener() {
      override fun onClick(e: MouseEvent, clickCount: Int): Boolean {
        val path = tree.getClosestPathForLocation(e.x, e.y)
        val bounds = if (path == null) null else tree.getPathBounds(path)
        if (bounds == null || bounds.x > e.x || bounds.y > e.y || bounds.y + bounds.height < e.y) return false
        navigateSelectedElement()
        return true
      }
    }.installOn(tree)

    for (filter in fileStructureFilters) {
      addCheckbox(chkPanel, filter)
    }

    for (provider in fileStructureNodeProviders) {
      addCheckbox(chkPanel, provider)
    }
    val topPanel = JPanel(BorderLayout())
    topPanel.add(chkPanel, BorderLayout.WEST)

    topPanel.add(createSettingsButton(), BorderLayout.EAST)

    topPanel.setBackground(JBUI.CurrentTheme.Popup.toolbarPanelColor())
    val prefSize = topPanel.getPreferredSize()
    if (singleRow) {
      prefSize.height = JBUI.CurrentTheme.Popup.toolbarHeight()
    }
    topPanel.preferredSize = prefSize
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

    return wrapComponent(panel, UiDataProvider { sink: DataSink? -> uiDataSnapshot(sink!!) })
  }

  private fun uiDataSnapshot(sink: DataSink) {
    sink.set<Project?>(CommonDataKeys.PROJECT, myProject)
    sink.set<FileEditor?>(PlatformCoreDataKeys.FILE_EDITOR, myFileEditor)
    if (myFileEditor is TextEditor) {
      sink.set<Editor?>(OpenFileDescriptor.NAVIGATE_IN_EDITOR, myFileEditor.getEditor())
    }
    sink.set<JBPopup?>(LangDataKeys.POSITION_ADJUSTER_POPUP, myPopup)
    sink.set<CopyProvider?>(PlatformDataKeys.COPY_PROVIDER, myCopyPasteDelegator.copyProvider)
    sink.set<TreeExpander?>(PlatformDataKeys.TREE_EXPANDER, myTreeExpander)

    val selection = tree.getSelectionPaths()
    val selectedElements: JBIterable<Any?> = JBIterable.of(*selection)
      .filterMap(Function { o: TreePath? -> StructureViewComponent.unwrapValue(o!!.lastPathComponent) })
    sink.lazy<PsiElement?>(CommonDataKeys.PSI_ELEMENT) { selectedElements.filter(PsiElement::class.java).first() }
    sink.lazy<Array<PsiElement?>?>(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY) {
      PsiUtilCore.toPsiElementArray(selectedElements.filter(PsiElement::class.java).toList())
    }
    sink.lazy<Navigatable?>(CommonDataKeys.NAVIGATABLE) { selectedElements.filter(Navigatable::class.java).first() }
    sink.lazy<Array<Navigatable?>?>(CommonDataKeys.NAVIGATABLE_ARRAY) {
      val result = selectedElements.filter(Navigatable::class.java).toList()
      if (result.isEmpty()) null else result.toArray<Navigatable?>(Navigatable.EMPTY_NAVIGATABLE_ARRAY)
    }
  }

  private fun createSettingsButton(): JComponent {
    val label = JLabel(AllIcons.General.GearPlain)
    label.setBorder(JBUI.Borders.empty(0, 4))
    label.setHorizontalAlignment(SwingConstants.RIGHT)
    label.setVerticalAlignment(SwingConstants.CENTER)
    label.getAccessibleContext().setAccessibleName(LangBundle.message("file.structure.settings.accessible.name"))

    val sorters = createSorters()
    object : ClickListener() {
      override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
        val group = DefaultActionGroup()
        if (!sorters.isEmpty()) {
          group.addAll(sorters)
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

  private fun createSorters(): MutableList<AnAction?> {
    val actions = mutableListOf<AnAction>()
    for (sorter in myTreeModel.getSorters()) {
      if (sorter.isVisible()) {
        actions.add(MyTreeActionWrapper(sorter))
      }
    }
    return actions
  }

  private inner class MyTreeActionWrapper(private val myAction: TreeAction) : TreeActionWrapper(
    myAction, myTreeActionsOwner) {
    init {
      myTreeActionsOwner.setActionIncluded(myAction, getDefaultValue(myAction))
    }

    override fun update(e: AnActionEvent) {
      super.update(e)
      e.presentation.setIcon(null)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
      return ActionUpdateThread.BGT
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      val actionState = TreeModelWrapper.shouldRevert(myAction) != state
      myTreeActionsOwner.setActionIncluded(myAction, actionState)
      saveState(myAction, state)
      rebuild(false).onProcessed {
        if (mySpeedSearch!!.isPopupActive) {
          mySpeedSearch.refreshSelection()
        }
      }
    }
  }

  private val selectedNode: AbstractTreeNode<*>?
    get() {
      val path = tree.selectionPath
      val o = StructureViewComponent.unwrapNavigatable(if (path == null) null else path.lastPathComponent)
      return o as? AbstractTreeNode<*>
    }

  private fun navigateSelectedElement(): Boolean {
    val selectedNode: AbstractTreeNode<*>? = this.selectedNode
    if (ApplicationManager.getApplication().isInternal()) {
      val enteredPrefix = mySpeedSearch!!.enteredPrefix
      val itemText = getSpeedSearchText(selectedNode)
      if (StringUtil.isNotEmpty(enteredPrefix) && StringUtil.isNotEmpty(itemText)) {
        LOG.info("Chosen in file structure popup by prefix '$enteredPrefix': '$itemText'")
      }
    }

    val succeeded = Ref<Boolean?>()
    val commandProcessor = CommandProcessor.getInstance()
    commandProcessor.executeCommand(myProject, Runnable {
      if (selectedNode != null) {
        if (selectedNode.canNavigateToSource()) {
          selectedNode.navigate(true)
          myCallbackAfterNavigation?.accept(selectedNode)
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
    return succeeded.get()!!
  }

  private fun addCheckbox(panel: JPanel, action: TreeAction?) {
    var text =
      if (action is FileStructureFilter) action.getCheckBoxText() else if (action is FileStructureNodeProvider<*>) action.getCheckBoxText() else null

    if (text == null) return

    val shortcuts = extractShortcutFor(action!!)


    val checkBox = JBCheckBox()
    checkBox.setOpaque(false)
    UIUtil.applyStyle(UIUtil.ComponentStyle.SMALL, checkBox)

    val selected = getDefaultValue(action)
    checkBox.setSelected(selected)
    val isRevertedStructureFilter = action is FileStructureFilter && action.isReverted()
    myTreeActionsOwner.setActionIncluded(action, isRevertedStructureFilter != selected)
    checkBox.addActionListener {
      logFileStructureCheckboxClick(action)
      val state = checkBox.isSelected
      if (!myAutoClicked.contains(checkBox)) {
        saveState(action, state)
      }
      myTreeActionsOwner.setActionIncluded(action, isRevertedStructureFilter != state)
      rebuild(false).onProcessed {
        if (mySpeedSearch!!.isPopupActive) {
          mySpeedSearch.refreshSelection()
        }
      }
    }
    checkBox.setFocusable(false)

    if (shortcuts.isNotEmpty()) {
      text += " (${KeymapUtil.getShortcutText(shortcuts[0]!!)})" 
      DumbAwareAction.create { _: AnActionEvent -> checkBox.doClick() }
        .registerCustomShortcutSet(CustomShortcutSet(*shortcuts), tree)
    }
    checkBox.text = text
    panel.add(checkBox)

    myCheckBoxes[action.name] = checkBox
  }

  private fun logFileStructureCheckboxClick(action: TreeAction?) {
    val fileType = myFileEditor.file.fileType
    val language = if (fileType is LanguageFileType) fileType.language else null

    ActionsCollectorImpl.recordActionInvoked(myProject) {
      add(EventFields.PluginInfoFromInstance.with(action!!))
      add(EventFields.ActionPlace.with(ActionPlaces.FILE_STRUCTURE_POPUP))
      add(EventFields.CurrentFile.with(language))
      add(ActionsEventLogGroup.ACTION_CLASS.with(action.javaClass))
      add(ActionsEventLogGroup.ACTION_ID.with(action.javaClass.name))
    }
  }

  private fun rebuild(refilterOnly: Boolean): Promise<Void?> {
    val selection = JBIterable.of(*tree.selectionPaths)
      .filterMap { o -> StructureViewComponent.unwrapValue(o.lastPathComponent) }.first()
    return rebuildAndSelect(refilterOnly, selection, null).then { _ -> null }
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
        myFilteringStructure.refilter()
        myStructureTreeModel.invalidateAsync().thenRun(Runnable {
          (if (selection == null) myAsyncTreeModel.accept { TreeVisitor.Action.CONTINUE } else select(selection))
            .onError {
              result.setError("rejected")
              mySpeedSearch!!.refreshSelection() // Selection failed, let the speed search reflect that by coloring itself red.
            }
            .onSuccess { p: TreePath? ->
              EdtInvocationManager.invokeLaterIfNeeded(Runnable {
                //maybe readaction
                WriteIntentReadAction.run(Runnable {
                  TreeUtil.expand(this.tree,
                                  if (myTreeModel is StructureViewCompositeModel)
                                    3
                                  else
                                    2)
                  TreeUtil.ensureSelection(tree)
                  mySpeedSearch!!.refreshSelection()
                  result.setResult(p)
                  FileStructurePopupTimeTracker.logRebuildTime(System.nanoTime() - finalLastRebuildStartTime!!)
                })
              })
            }
        })
      }
      else {
        myTreeStructure.rebuildTree()
        myStructureTreeModel.invalidateAsync()
          .thenRun(Runnable { rebuildAndSelect(true, selection, finalLastRebuildStartTime).processed(result) })
      }
    }).onError { throwable -> result.setError(throwable) }
    return result
  }

  override fun setTitle(@NlsContexts.PopupTitle title: @NlsContexts.PopupTitle String?) {
    myTitle = title
  }

  @get:TestOnly
  val speedSearch: TreeSpeedSearch?
    get() = mySpeedSearch

  @TestOnly
  fun setSearchFilterForTests(filter: String?) {
    myTestSearchFilter = filter
  }

  fun setTreeActionState(action: TreeAction, state: Boolean) {
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
    private val myVisibleParents: MutableSet<Any?> = HashSet()
    private val isUnitTest = ApplicationManager.getApplication().isUnitTestMode()

    override fun shouldBeShowing(value: Any?): Boolean {
      if (!isShouldNarrowDown) return true

      val filter: String? = this.searchPrefix
      if (!StringUtil.equals(myLastFilter, filter)) {
        myVisibleParents.clear()
        myLastFilter = filter
      }
      if (filter != null) {
        if (myVisibleParents.contains(value)) {
          return true
        }

        val text = getSpeedSearchText(value)
        if (text == null) return false

        if (matches(filter, text)) {
          var o = value
          while (o is FilteringNode && (o.parent.also { o = it }) != null) {
            myVisibleParents.add(o)
          }
          return true
        }
        else {
          return false
        }
      }
      return true
    }

    fun matches(filter: String, text: String): Boolean {
      return (isUnitTest || mySpeedSearch!!.isPopupActive) &&
             StringUtil.isNotEmpty(filter) && mySpeedSearch!!.comparator.matchingFragments(filter, text) != null
    }
  }

  private val searchPrefix: String?
    get() {
      if (ApplicationManager.getApplication().isUnitTestMode()) return myTestSearchFilter

      return if (mySpeedSearch != null && !StringUtil.isEmpty(mySpeedSearch.enteredPrefix))
        mySpeedSearch.enteredPrefix
      else
        null
    }

  private inner class MyTreeSpeedSearch : TreeSpeedSearch(this.tree, true, null, java.util.function.Function { path: TreePath? ->
    getSpeedSearchText(
      TreeUtil.getLastUserObject(path))
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

    public override fun findElement(s: String): Any? {
      val elements = SpeedSearchObjectWithWeight.findElement(s, this)
      val best = elements.firstOrNull()
      if (best == null) return null
      if (myInitialElement is PsiElement) {
        // find children of the initial element
        val bestForParent =
          find(myInitialElement, elements, BiPredicate { parent: PsiElement?, path: TreePath? -> isParent(parent!!, path) })
        if (bestForParent != null) return bestForParent.node
        // find siblings of the initial element
        val parent = myInitialElement.getParent()
        if (parent != null) {
          val bestSibling =
            find(parent, elements, BiPredicate { parent: PsiElement?, path: TreePath? -> isParent(parent!!, path) })
          if (bestSibling != null) return bestSibling.node
        }
        // find grand children of the initial element
        val bestForAncestor =
          find(myInitialElement, elements, BiPredicate { ancestor: PsiElement?, path: TreePath? -> isAncestor(ancestor!!, path) })
        if (bestForAncestor != null) return bestForAncestor.node
      }
      return best.node
    }
  }

  internal class MyTree(treeModel: TreeModel?) : DnDAwareTree(treeModel), PlaceProvider {
    init {
      setRootVisible(false)
      setShowsRootHandles(true)

      HintUpdateSupply.installHintUpdateSupply(this, Function { o: Any? ->
        val value = StructureViewComponent.unwrapValue(o)
        value as? PsiElement
      })
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

  private inner class ToggleNarrowDownAction : ToggleAction(IdeBundle.message("checkbox.narrow.down.on.typing")) {
    override fun isSelected(e: AnActionEvent): Boolean {
      return isShouldNarrowDown
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
      return ActionUpdateThread.BGT
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      PropertiesComponent.getInstance().setValue(NARROW_DOWN_PROPERTY_KEY, state.toString())
      if (mySpeedSearch!!.isPopupActive && !StringUtil.isEmpty(mySpeedSearch.enteredPrefix)) {
        rebuild(true)
      }
    }
  }

  companion object {
    private val LOG = Logger.getInstance(FileStructurePopup::class.java)

    @NonNls
    private const val NARROW_DOWN_PROPERTY_KEY: @NonNls String = "FileStructurePopup.narrowDown"

    private val isShouldNarrowDown: Boolean
      get() = PropertiesComponent.getInstance().getBoolean(NARROW_DOWN_PROPERTY_KEY, true)

    @get:NonNls
    private val dimensionServiceKey: @NonNls String
      get() = "StructurePopup"

    private fun findClosestPsiElement(
      element: PsiElement,
      adjusted: TreePath,
      treeModel: TreeModel,
    ): Any? {
      val range = element.getTextRange()
      if (range == null) return null
      val parent = adjusted.lastPathComponent
      var minDistance = 0
      var minChild: Any? = null
      var i = 0
      val count = treeModel.getChildCount(parent)
      while (i < count) {
        val child = treeModel.getChild(parent, i)
        val value = StructureViewComponent.unwrapValue(child)
        if (value is StubBasedPsiElement<*> && value.getStub() != null) {
          i++
          continue
        }
        val r = if (value is PsiElement) value.getTextRange() else null
        if (r == null) {
          i++
          continue
        }
        val distance = TextRangeUtil.getDistance(range, r)
        if (minChild == null || distance < minDistance) {
          minDistance = distance
          minChild = child
        }
        i++
      }
      return minChild
    }

    fun extractShortcutFor(action: TreeAction): Array<Shortcut?> {
      if (action is ActionShortcutProvider) {
        val actionId = (action as ActionShortcutProvider).getActionIdForShortcut()
        return KeymapUtil.getActiveKeymapShortcuts(actionId).getShortcuts()
      }
      return if (action is FileStructureFilter) action.getShortcut() else (action as FileStructureNodeProvider<*>).getShortcut()
    }

    private fun getDefaultValue(action: TreeAction): Boolean {
      val propertyName = if (action is PropertyOwner) (action as PropertyOwner).getPropertyName() else action.getName()
      val defaultValue = Sorter.ALPHA_SORTER == action || hasEnabledStateByDefault(action)
      return PropertiesComponent.getInstance().getBoolean(TreeStructureUtil.getPropertyName(propertyName), defaultValue)
    }

    private fun hasEnabledStateByDefault(action: TreeAction): Boolean {
      return action is TreeActionWithDefaultState && action.isEnabledByDefault()
    }

    private fun saveState(action: TreeAction, state: Boolean) {
      val propertyName = if (action is PropertyOwner) (action as PropertyOwner).getPropertyName() else action.getName()
      val defaultValue = Sorter.ALPHA_SORTER == action || hasEnabledStateByDefault(action)
      PropertiesComponent.getInstance().setValue(TreeStructureUtil.getPropertyName(propertyName), state, defaultValue)
    }

    fun getSpeedSearchText(`object`: Any?): String? {
      val text = `object`?.toString()
      val value = StructureViewComponent.unwrapWrapper(`object`)
      if (text != null) {
        if (value is SearchableTextProvider) {
          val searchableText = value.searchableText
          if (searchableText != null) return searchableText
        }
        if (value is PsiTreeElementBase<*> && value.isSearchInLocationString) {
          val locationString = value.locationString
          if (!StringUtil.isEmpty(locationString)) {
            var locationPrefix: String? = null
            var locationSuffix: String? = null
            if (value is LocationPresentation) {
              locationPrefix = (value as LocationPresentation).getLocationPrefix()
              locationSuffix = (value as LocationPresentation).getLocationSuffix()
            }

            return text +
                   StringUtil.notNullize(locationPrefix, LocationPresentation.DEFAULT_LOCATION_PREFIX) +
                   locationString +
                   StringUtil.notNullize(locationSuffix, LocationPresentation.DEFAULT_LOCATION_SUFFIX)
          }
        }
        return text
      }
      // NB!: this point is achievable if the following method returns null
      // see com.intellij.ide.util.treeView.NodeDescriptor.toString
      if (value is TreeElement) {
        return ReadAction.compute<T?, E?>(ThrowableComputable {
          if (value is SearchableTextProvider) {
            val searchableText = value.searchableText
            if (searchableText != null) return@compute searchableText
          }
          value.getPresentation().getPresentableText()
        })
      }

      return null
    }

    private fun find(
      element: PsiElement,
      objects: MutableList<out SpeedSearchObjectWithWeight?>,
      predicate: BiPredicate<in PsiElement?, in TreePath?>,
    ): SpeedSearchObjectWithWeight? {
      return objects.find { `object`: SpeedSearchObjectWithWeight? ->
        predicate.test(element,
                       (`object`!!.node as? TreePath))
      }
    }

    private fun isElement(element: PsiElement, path: TreePath?): Boolean {
      return element == StructureViewComponent.unwrapValue(TreeUtil.getLastUserObject(FilteringNode::class.java, path))
    }

    private fun isParent(parent: PsiElement, path: TreePath?): Boolean {
      return path != null && isElement(parent, path.parentPath)
    }

    private fun isAncestor(ancestor: PsiElement, path: TreePath?): Boolean {
      var path = path
      while (path != null) {
        if (isElement(ancestor, path)) return true
        path = path.parentPath
      }
      return false
    }
  }
}