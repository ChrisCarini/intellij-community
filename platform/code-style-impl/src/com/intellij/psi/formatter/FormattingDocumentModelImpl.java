// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.formatter;

import com.intellij.application.options.CodeStyle;
import com.intellij.formatting.FormattingDocumentModel;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.impl.PsiDocumentManagerBase;
import com.intellij.psi.impl.PsiToDocumentSynchronizer;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtilBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class FormattingDocumentModelImpl implements FormattingDocumentModel {
  private final WhiteSpaceFormattingStrategy myWhiteSpaceStrategy;
  private final @NotNull Document myDocument;
  private final @NotNull PsiFile myPsiFile;

  private static final Logger LOG = Logger.getInstance(FormattingDocumentModelImpl.class);
  private final CodeStyleSettings mySettings;

  public FormattingDocumentModelImpl(final @NotNull Document document, @NotNull PsiFile psiFile) {
    myDocument = document;
    myPsiFile = psiFile;
    Language language = psiFile.getLanguage();
    myWhiteSpaceStrategy = WhiteSpaceFormattingStrategyFactory.getStrategy(language);
    mySettings = CodeStyle.getSettings(psiFile);
  }

  public static FormattingDocumentModelImpl createOn(@NotNull PsiFile psiFile) {
    Document document = getDocumentToBeUsedFor(psiFile);
    if (document != null) {
      checkDocument(psiFile, document);
      return new FormattingDocumentModelImpl(document, psiFile);
    }
    else {
      return new FormattingDocumentModelImpl(new DocumentImpl(psiFile.getViewProvider().getContents(), true), psiFile);
    }
  }

  private static void checkDocument(@NotNull PsiFile psiFile, @NotNull Document document) {
    if (psiFile.getTextLength() != document.getTextLength()) {
      LOG.error(DebugUtil.diagnosePsiDocumentInconsistency(psiFile, document));
    }
  }

  public static @Nullable Document getDocumentToBeUsedFor(final PsiFile psiFile) {
    final Project project = psiFile.getProject();
    if (!psiFile.isPhysical()) {
      return getDocumentForNonPhysicalFile(psiFile);
    }
    final Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
    if (document != null) {
      if (PsiDocumentManager.getInstance(project).isUncommited(document)) return null;
      PsiToDocumentSynchronizer synchronizer = ((PsiDocumentManagerBase)PsiDocumentManager.getInstance(project)).getSynchronizer();
      if (synchronizer.isDocumentAffectedByTransactions(document)) return null;
    }
    return document;
  }

  private static @NotNull Document getDocumentForNonPhysicalFile(PsiFile psiFile) {
    Document document = psiFile.getViewProvider().getDocument();
    if (document != null && document.getTextLength() == psiFile.getTextLength()) {
      return document;
    }
    return new DocumentImpl(psiFile.getText(), true);
  }

  @Override
  public int getLineNumber(int offset) {
    if (offset > myDocument.getTextLength()) {
      LOG.error(String.format("Invalid offset detected (%d). Document length: %d. Target file: %s",
                              offset, myDocument.getTextLength(), myPsiFile));
    }
    return myDocument.getLineNumber(offset);
  }

  @Override
  public int getLineStartOffset(int line) {
    return myDocument.getLineStartOffset(line);
  }

  @Override
  public CharSequence getText(final TextRange textRange) {
    if (textRange.getStartOffset() < 0 || textRange.getEndOffset() > myDocument.getTextLength()) {
      LOG.error(String.format(
        "Please submit a ticket to the tracker and attach current source file to it!%nInvalid processing detected: given text "
        + "range (%s) targets non-existing regions (the boundaries are [0; %d)). File's language: %s",
        textRange, myDocument.getTextLength(), myPsiFile.getLanguage()
      ));
    }
    return myDocument.getCharsSequence().subSequence(textRange.getStartOffset(), textRange.getEndOffset());
  }

  @Override
  public int getTextLength() {
    return myDocument.getTextLength();
  }

  @Override
  public @NotNull Document getDocument() {
    return myDocument;
  }

  public @NotNull PsiFile getFile() {
    return myPsiFile;
  }

  @Override
  public boolean containsWhiteSpaceSymbolsOnly(int startOffset, int endOffset) {
    Language startElementLanguage = getLanguageByOffset(startOffset);
    if (myWhiteSpaceStrategy.check(startElementLanguage, myDocument.getCharsSequence(), startOffset, endOffset) >= endOffset) {
      return true;
    }
    PsiElement injectedElement = InjectedLanguageUtilBase.findElementAtNoCommit(myPsiFile, startOffset);
    if (injectedElement != null) {
      Language injectedLanguage = injectedElement.getLanguage();
      if (!injectedLanguage.equals(myPsiFile.getLanguage())) {
        WhiteSpaceFormattingStrategy localStrategy = WhiteSpaceFormattingStrategyFactory.getStrategy(injectedLanguage);
        String unescapedText = InjectedLanguageUtilBase.getUnescapedLeafText(injectedElement, true);
        if (unescapedText != null) {
          return localStrategy.check(unescapedText, 0, unescapedText.length()) >= unescapedText.length();
        }

        return localStrategy.check(myDocument.getCharsSequence(), startOffset, endOffset) >= endOffset;
      }
    }
    return false;
  }

  @Override
  public @NotNull CharSequence adjustWhiteSpaceIfNecessary(@NotNull CharSequence whiteSpaceText, int startOffset, int endOffset,
                                                           ASTNode nodeAfter, boolean changedViaPsi)
  {
    if (!changedViaPsi) {
      return myWhiteSpaceStrategy.adjustWhiteSpaceIfNecessary(whiteSpaceText, myDocument.getCharsSequence(), startOffset, endOffset,
                                                              mySettings, nodeAfter);
    }

    final PsiElement element = myPsiFile.findElementAt(startOffset);
    if (element == null) {
      return whiteSpaceText;
    }
    else {
      return myWhiteSpaceStrategy.adjustWhiteSpaceIfNecessary(whiteSpaceText, element, startOffset, endOffset, mySettings);
    }
  }

  public static boolean canUseDocumentModel(@NotNull Document document,@NotNull PsiFile psiFile) {
    PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(psiFile.getProject());
    return !psiDocumentManager.isUncommited(document) &&
           !psiDocumentManager.isDocumentBlockedByPsi(document) &&
           psiFile.getText().equals(document.getText());
  }

  private Language getLanguageByOffset(int offset) {
    if (offset < myPsiFile.getTextLength()) {
      PsiElement element = myPsiFile.findElementAt(offset);
      if (element != null) {
        return element.getLanguage();
      }
    }
    return null;
  }
}
