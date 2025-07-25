/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.fileEditor.impl.text;

import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;

/**
 * Implementations of this interface are expected to provide correct {@link Object#equals(Object)} and {@link Object#hashCode()} implementations.
 */
@FunctionalInterface
public interface CodeFoldingState {
  void setToEditor(@NotNull Editor editor);

  @Override
  boolean equals(Object obj);

  @Override
  int hashCode();
}
