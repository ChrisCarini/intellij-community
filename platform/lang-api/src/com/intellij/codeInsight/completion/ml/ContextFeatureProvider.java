// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.completion.ml;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageExtension;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A context feature provider provides additional information about the completion context which can be used by lookup element
 * feature providers or the ml algorithm later on to compute optimal proposal rankings.
 * <p>
 * See FAQ in {@link MLFeatureValue}
 *
 * @see ElementFeatureProvider
 * @see com.intellij.codeInsight.completion.CompletionContributor
 */
@ApiStatus.Internal
public interface ContextFeatureProvider {
  LanguageExtension<ContextFeatureProvider> EP_NAME = new LanguageExtension<>("com.intellij.completion.ml.contextFeatures");

  static @Unmodifiable @NotNull List<ContextFeatureProvider> forLanguage(@NotNull Language language) {
    return EP_NAME.allForLanguageOrAny(language);
  }

  /**
   * @return name of feature provider. Must be unique inside inside the same language.
   */
  @NotNull
  String getName();

  /**
   * @deprecated Use {@link #calculateFeatures(CompletionEnvironment)} instead
   */
  @Deprecated(forRemoval = true)
  default @NotNull Map<String, MLFeatureValue> calculateFeatures(@NotNull Lookup lookup) {
    return Collections.emptyMap();
  }

  /**
   * Invokes once when completion session is started with read access
   *
   * @param environment describes code completion session
   * @return container with all features calculated
   */
  default @NotNull Map<String, MLFeatureValue> calculateFeatures(@NotNull CompletionEnvironment environment) {
    return calculateFeatures(environment.getLookup());
  }
}
