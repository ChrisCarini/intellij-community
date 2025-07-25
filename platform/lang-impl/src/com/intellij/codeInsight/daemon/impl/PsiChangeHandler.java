// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeInsight.daemon.ChangeLocalityDetector;
import com.intellij.codeInsight.multiverse.CodeInsightContexts;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ProjectDisposeAwareDocumentListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorMarkupModel;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiDocumentManagerImpl;
import com.intellij.psi.impl.PsiTreeChangeEventImpl;
import com.intellij.util.Alarm;
import com.intellij.util.SlowOperations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;

final class PsiChangeHandler extends PsiTreeChangeAdapter implements Runnable {
  private static final ExtensionPointName<ChangeLocalityDetector> EP_NAME = new ExtensionPointName<>("com.intellij.daemon.changeLocalityDetector");

  private final Project myProject;
  private final Map<Document, List<Change>> changedElements = new WeakHashMap<>(); // guarded by changedElements
  private final FileStatusMap myFileStatusMap;
  private final Alarm myUpdateFileStatusAlarm;

  private record Change(@NotNull PsiElement psiElement, boolean whiteSpaceOptimizationAllowed) {}

  PsiChangeHandler(@NotNull Project project, @NotNull DaemonCodeAnalyzerEx daemonCodeAnalyzerEx, @NotNull Disposable parentDisposable) {
    myProject = project;
    myFileStatusMap = daemonCodeAnalyzerEx.getFileStatusMap();
    DocumentAfterCommitListener.listen(project, parentDisposable, document -> updateChangesForDocument(daemonCodeAnalyzerEx, document));
    EditorFactory.getInstance().getEventMulticaster().addDocumentListener(ProjectDisposeAwareDocumentListener.create(project, new DocumentListener() {
      @Override
      public void documentChanged(@NotNull DocumentEvent event) {
        myFileStatusMap.addDocumentCompositeDirtyRange(event);
      }
    }), parentDisposable);
    myUpdateFileStatusAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable);
  }

  private void updateChangesForDocument(@NotNull DaemonCodeAnalyzerEx daemonCodeAnalyzerEx, @NotNull Document document) {
    Application application = ApplicationManager.getApplication();
    application.assertIsDispatchThread();// to prevent changedElements corruption
    if (myProject.isDisposed()) {
      return;
    }
    // don't create PSI for files in other projects
    PsiFile psiFile = getRawCachedPsiFile(document);
    if (psiFile != null) {
      if (daemonCodeAnalyzerEx instanceof DaemonCodeAnalyzerImpl impl && impl.isUpdateByTimerEnabled()) {
        // even though there maybe no PSI events, we need to re-highlight the changed range
        // e.g. when the user backspace-d and the quickly re-typed back, or modified and then quickly undid
        addChangesFromCompositeDirtyRange(psiFile, document, UpdateHighlightersUtil.isWhitespaceOptimizationAllowed(document));
      }
      synchronized (changedElements) {
        List<Change> toUpdate = changedElements.get(document);
        if (toUpdate == null) {
          // The document has been changed, but psi hasn't
          // We may still need to rehighlight the file if there were changes inside highlighted ranges.
          if (!UpdateHighlightersUtil.isWhitespaceOptimizationAllowed(document)) {
            toUpdate = new ArrayList<>();
            toUpdate.add(new Change(psiFile, true));
            changedElements.putIfAbsent(document, toUpdate);
          }
        }
      }
    }
    Editor selectedEditor = FileEditorManager.getInstance(myProject).getSelectedTextEditor();
    PsiFile selectedPsiFile;
    if (selectedEditor == null) {
      selectedPsiFile = null;
    }
    else {
      selectedPsiFile = getRawCachedPsiFile(selectedEditor.getDocument());
    }
    if (selectedPsiFile != null && !application.isUnitTestMode()) {
      application.invokeLater(() -> {
        if (!selectedEditor.isDisposed() &&
            selectedEditor.getMarkupModel() instanceof EditorMarkupModel markupModel) {
          ErrorStripeUpdateManager.getInstance(myProject).setOrRefreshErrorStripeRenderer(markupModel, selectedPsiFile);
        }
      }, ModalityState.stateForComponent(selectedEditor.getComponent()), myProject.getDisposed());
    }

    synchronized (myUpdateFileStatusAlarm) {
      myUpdateFileStatusAlarm.cancelAllRequests();
      myUpdateFileStatusAlarm.addRequest(this, 0);
    }
  }

  private PsiFile getRawCachedPsiFile(@NotNull Document document) {
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    return virtualFile == null || !virtualFile.isValid() ? null : TextEditorBackgroundHighlighter.getCachedFileToHighlight(myProject, virtualFile, CodeInsightContexts.anyContext());
  }

  private void addChangesFromCompositeDirtyRange(@NotNull PsiFile psiFile,
                                                 @NotNull Document document, boolean whiteSpaceOptimizationAllowed) {
    TextRange compositeDirtyRange = myFileStatusMap.getCompositeDocumentDirtyRange(document);
    if (compositeDirtyRange != null) {
      PsiElement startElement = psiFile.findElementAt(Math.min(psiFile.getTextLength(), compositeDirtyRange.getStartOffset()));
      PsiElement endElement = psiFile.findElementAt(Math.min(psiFile.getTextLength(), compositeDirtyRange.getEndOffset()));
      if (startElement != null) {
        storeChangedElement(startElement, document, whiteSpaceOptimizationAllowed);
      }
      if (endElement != null && startElement != endElement) {
        storeChangedElement(endElement, document,  whiteSpaceOptimizationAllowed);
      }
    }
  }

  @Override
  public void childAdded(@NotNull PsiTreeChangeEvent event) {
    queueElement(event.getParent(), true, event.getFile());
  }

  @Override
  public void childRemoved(@NotNull PsiTreeChangeEvent event) {
    queueElement(event.getParent(), true, event.getFile());
  }

  @Override
  public void childReplaced(@NotNull PsiTreeChangeEvent event) {
    queueElement(event.getNewChild(), typesEqual(event.getNewChild(), event.getOldChild()), event.getFile());
  }

  private static boolean typesEqual(PsiElement newChild, PsiElement oldChild) {
    return newChild != null && oldChild != null && newChild.getClass() == oldChild.getClass();
  }

  @Override
  public void childrenChanged(@NotNull PsiTreeChangeEvent event) {
    if (((PsiTreeChangeEventImpl)event).isGenericChange()) {
      return;
    }
    queueElement(event.getParent(), true, event.getFile());
  }

  @Override
  public void beforeChildMovement(@NotNull PsiTreeChangeEvent event) {
    queueElement(event.getOldParent(), true, event.getFile());
    queueElement(event.getNewParent(), true, event.getFile());
  }

  @Override
  public void beforeChildrenChange(@NotNull PsiTreeChangeEvent event) {
    // this event sent always before every PSI change, even not significant one (like after quick typing/backspacing char)
    // mark file dirty just in case
    PsiFile psiFile = event.getFile();
    if (psiFile != null) {
      Document document = PsiDocumentManager.getInstance(myProject).getCachedDocument(psiFile);
      if (document != null) {
        myFileStatusMap.markFileScopeDirtyDefensively(document, event);
      }
    }
  }

  @Override
  public void propertyChanged(@NotNull PsiTreeChangeEvent event) {
    String propertyName = event.getPropertyName();
    if (!propertyName.equals(PsiTreeChangeEvent.PROP_WRITABLE)) {
      Object oldValue = event.getOldValue();
      // ignore workspace.xml
      if (!(oldValue instanceof VirtualFile vf) || shouldHandle(vf)) {
        myFileStatusMap.markAllFilesDirty(event);
      }
    }
  }

  private void queueElement(@NotNull PsiElement child, boolean whitespaceOptimizationAllowed, @Nullable PsiFile psiFile) {
    if (psiFile == null) {
      psiFile = child.getContainingFile();
    }
    if (psiFile == null) {
      myFileStatusMap.markAllFilesDirty(child);
      return;
    }

    if (!child.isValid()) {
      return;
    }

    PsiDocumentManagerImpl pdm = (PsiDocumentManagerImpl)PsiDocumentManager.getInstance(myProject);
    Document document = pdm.getCachedDocument(psiFile);
    if (document != null) {
      VirtualFile virtualFile = psiFile.getVirtualFile();
      if (virtualFile != null && ProjectFileIndex.getInstance(myProject).isExcluded(virtualFile)) {
        // ignore changes in excluded files
        return;
      }
      if (pdm.getSynchronizer().getTransaction(document) == null) {
        // content reload, language level change or some other big change
        myFileStatusMap.markAllFilesDirty(child);
        return;
      }

      storeChangedElement(child, document, whitespaceOptimizationAllowed);
    }
  }

  private void storeChangedElement(@NotNull PsiElement child, @NotNull Document document, boolean whitespaceOptimizationAllowed) {
    synchronized (changedElements) {
      List<Change> toUpdate = changedElements.computeIfAbsent(document, __->new ArrayList<>());
      toUpdate.add(new Change(child, whitespaceOptimizationAllowed));
    }
  }

  // handle queued elements
  @Override
  public void run() {
    ApplicationManager.getApplication().assertIsNonDispatchThread();

    ReadAction.run(() -> {
      // assume changedElement won't change under read action
      flushUpdateFileStatusQueue();
    });
  }

  void flushUpdateFileStatusQueue() {
    ApplicationManager.getApplication().assertReadAccessAllowed(); // only inside read/write action we can modify changedUpdate
    ApplicationManager.getApplication().assertIsNonDispatchThread();
    List<Map.Entry<Document, List<Change>>> entries;
    synchronized (changedElements) {
      entries = new ArrayList<>(changedElements.entrySet());
      changedElements.clear();
    }
    for (Map.Entry<Document, List<Change>> entry : entries) {
      Document document = entry.getKey();
      List<Change> changes = entry.getValue();
      for (Change change : changes) {
        doUpdateChild(document, change.psiElement(), change.whiteSpaceOptimizationAllowed());
      }
    }
  }

  private void doUpdateChild(@NotNull Document document, @NotNull PsiElement child, boolean whitespaceOptimizationAllowed) {
    ApplicationManager.getApplication().assertIsNonDispatchThread();
    if (myProject.isDisposed() /*|| !child.isValid()*//* || document.getModificationStamp() != documentOldModificationStamp*/) {
      return;
    }
    PsiFile psiFile;
    try {
      psiFile = child.getContainingFile();
    }
    catch (PsiInvalidElementAccessException e) {
      return;
    }
    // CCE can be thrown from incorrectly implemented PSI, e.g.
    // in GoStubbedElementImpl: public GoFile getContainingFile() { return (GoFile)super.getContainingFile(); }
    catch (ClassCastException e) {
      myFileStatusMap.markAllFilesDirty(e);
      return;
    }
    if (psiFile == null || psiFile instanceof PsiCompiledElement) {
      myFileStatusMap.markAllFilesDirty(child);
      return;
    }
    VirtualFile virtualFile = psiFile.getVirtualFile();
    if (virtualFile != null && !shouldHandle(virtualFile)) {
      // ignore workspace.xml
      return;
    }

    if (!psiFile.getViewProvider().isPhysical()) {
      myFileStatusMap.markWholeFileScopeDirty(document, "Non-physical file update: " + psiFile);
      return;
    }

    TextRange existingDirtyScope = myFileStatusMap.getFileDirtyScopeForAllPassesCombined(document);
    PsiElement element = whitespaceOptimizationAllowed && UpdateHighlightersUtil.isWhitespaceOptimizationAllowed(document) ? child : child.getParent();
    while (true) {
      if (element == null || element instanceof PsiFile || element instanceof PsiDirectory) {
        myFileStatusMap.markAllFilesDirty("Top element: " + element+"; changed child: "+child);
        break;
      }

      PsiElement scope = getChangeHighlightingScope(element);
      if (scope != null) {
        TextRange scopeRange = scope.getTextRange();
        // if some unrelated scope already marked dirty, we shouldn't just add another scope and return,
        // because between these two dirty whitespaces might easily be some other non-whitespace PSI,
        // and this PSI element is not expected to be highlighted alone, which could lead to unexpected highlighter disappearances
        // see DaemonRespondToChangesTest.testPutArgumentsOnSeparateLinesIntentionMustNotRemoveErrorHighlighting
        if (existingDirtyScope == null || scopeRange.contains(existingDirtyScope)) {
          myFileStatusMap.markScopeDirty(document, scopeRange, scope);
          break;
        }
        existingDirtyScope = existingDirtyScope.union(scopeRange);
      }

      element = element.getParent();
    }
  }

  private boolean shouldHandle(@NotNull VirtualFile virtualFile) {
    try (AccessToken ignore = SlowOperations.knownIssue("IDEA-307614, EA-698479")) {
      return !ProjectUtil.isProjectOrWorkspaceFile(virtualFile) &&
             !ProjectRootManager.getInstance(myProject).getFileIndex().isExcluded(virtualFile);
    }
  }

  private static @Nullable PsiElement getChangeHighlightingScope(@NotNull PsiElement element) {
    DefaultChangeLocalityDetector defaultDetector = null;
    for (ChangeLocalityDetector detector : EP_NAME.getExtensionList()) {
      if (detector instanceof DefaultChangeLocalityDetector def) {
        // run default detector last
        assert defaultDetector == null : defaultDetector;
        defaultDetector = def;
        continue;
      }
      PsiElement scope = detector.getChangeHighlightingDirtyScopeFor(element);
      if (scope != null) {
        return scope;
      }
    }
    assert defaultDetector != null : "com.intellij.codeInsight.daemon.impl.DefaultChangeLocalityDetector is unregistered";
    return defaultDetector.getChangeHighlightingDirtyScopeFor(element);
  }

  @TestOnly
  void waitForUpdateFileStatusQueue() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    CountDownLatch s = new CountDownLatch(1);
    // synchronized to avoid data race when myUpdateFileStatusAlarm.cancel() in updateChangesForDocument called, then (from interleaved thread) waitForUpdateFileStatusQueue() called, then myUpdateFileStatusAlarm.addRequest() called, resulting in immediate return from waitForUpdateFileStatusQueue method because alarm is temporarily empty
    synchronized (myUpdateFileStatusAlarm) {
      myUpdateFileStatusAlarm.addRequest(() -> s.countDown(), 0);
    }
    try {
      s.await();
    }
    catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
