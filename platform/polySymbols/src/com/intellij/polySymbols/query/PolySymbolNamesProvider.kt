// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.query

import com.intellij.model.Pointer
import com.intellij.openapi.util.ModificationTracker
import com.intellij.polySymbols.PolySymbolQualifiedName

interface PolySymbolNamesProvider : ModificationTracker {

  fun createPointer(): Pointer<PolySymbolNamesProvider>

  fun getNames(qualifiedName: PolySymbolQualifiedName, target: Target): List<String>

  fun adjustRename(
    qualifiedName: PolySymbolQualifiedName,
    newName: String,
    occurence: String,
  ): String

  fun withRules(rules: List<PolySymbolNameConversionRules>): PolySymbolNamesProvider

  enum class Target {
    CODE_COMPLETION_VARIANTS,
    NAMES_MAP_STORAGE,
    NAMES_QUERY,
    RENAME_QUERY,
  }

}