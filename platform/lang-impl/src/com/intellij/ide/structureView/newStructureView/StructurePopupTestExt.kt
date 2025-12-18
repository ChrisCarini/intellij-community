// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.structureView.newStructureView

import com.intellij.openapi.Disposable
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

@ApiStatus.Internal
interface StructurePopupTestExt: Disposable {
  @TestOnly
  fun getSpeedSearch(): TreeSpeedSearch?

  @TestOnly
  fun setSearchFilterForTests(filter: String?)

  @TestOnly
  fun setTreeActionState(actionName: String, state: Boolean)

  @TestOnly
  fun initUi()

  @TestOnly
  fun getTree(): Tree
}