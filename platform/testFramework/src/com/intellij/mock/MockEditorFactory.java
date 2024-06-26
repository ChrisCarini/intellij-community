// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.mock;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.text.CharArrayCharSequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Stream;

public final class MockEditorFactory extends EditorFactory {
  public Document createDocument(String text) {
    return new DocumentImpl(text);
  }

  @Override
  public Editor createEditor(@NotNull Document document) {
    return null;
  }

  @Override
  public Editor createViewer(@NotNull Document document) {
    return null;
  }

  @Override
  public Editor createEditor(@NotNull Document document, Project project) {
    return null;
  }

  @Override
  public Editor createEditor(@NotNull Document document, @Nullable Project project, @Nullable EditorKind kind) {
    return null;
  }

  @Override
  public Editor createEditor(@NotNull Document document, Project project, @NotNull VirtualFile file, boolean isViewer) {
    return null;
  }

  @Override
  public Editor createEditor(@NotNull Document document,
                             Project project,
                             @NotNull VirtualFile file,
                             boolean isViewer,
                             @NotNull EditorKind kind) {
    return null;
  }

  @Override
  public Editor createEditor(final @NotNull Document document, final Project project, final @NotNull FileType fileType, final boolean isViewer) {
    return null;
  }

  @Override
  public Editor createViewer(@NotNull Document document, Project project) {
    return null;
  }

  @Override
  public Editor createViewer(@NotNull Document document, @Nullable Project project, @Nullable EditorKind kind) {
    return null;
  }

  @Override
  public void releaseEditor(@NotNull Editor editor) {
  }

  @Override
  public @NotNull Stream<Editor> editors(@NotNull Document document, @Nullable Project project) {
    return Stream.empty();
  }

  @Override
  public Editor @NotNull [] getAllEditors() {
    return Editor.EMPTY_ARRAY;
  }

  @Override
  public @NotNull List<Editor> getEditorList() {
    return List.of();
  }

  @Override
  public void addEditorFactoryListener(@NotNull EditorFactoryListener listener) {
  }

  @Override
  public void addEditorFactoryListener(@NotNull EditorFactoryListener listener, @NotNull Disposable parentDisposable) {
  }

  @Override
  public void removeEditorFactoryListener(@NotNull EditorFactoryListener listener) {
  }

  @Override
  public @NotNull EditorEventMulticaster getEventMulticaster() {
    return new MockEditorEventMulticaster();
  }

  @Override
  public @NotNull Document createDocument(@NotNull CharSequence text) {
    return new DocumentImpl(text);
  }

  @Override
  public @NotNull Document createDocument(char @NotNull [] text) {
    return createDocument(new CharArrayCharSequence(text));
  }

  @Override
  public void refreshAllEditors() {
  }

}
