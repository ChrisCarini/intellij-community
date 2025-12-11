// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.impl.uiModel

import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.vcs.FileStatus
import com.intellij.pom.Navigatable

class StructureUiTreeElementWrapper : StructureUiTreeElement {
  
  @Volatile
  private var delegate: StructureUiTreeElement? = null
  
  /**
   * Set the actual delegate. After this is called, all operations will be delegated.
   */
  fun setDelegate(node: StructureUiTreeElement) {
    delegate = node
  }
  
  override val id: Int
    get() = delegate?.id ?: -1
  
  override val parent: StructureUiTreeElement?
    get() = delegate?.parent

  override val indexInParent: Int
    get() = delegate?.indexInParent ?: -1

  override val presentation: ItemPresentation
    get() = delegate?.presentation ?: PresentationData("", "", null, null)
  
  override val speedSearchText: String?
    get() = delegate?.speedSearchText
  
  override val alwaysShowPlus: Boolean
    get() = delegate?.alwaysShowPlus ?: false
  
  override val alwaysLeaf: Boolean
    get() = delegate?.alwaysLeaf ?: false
  
  override val shouldAutoExpand: Boolean
    get() = delegate?.shouldAutoExpand ?: false
  
  override val navigatable: Navigatable?
    get() = delegate?.navigatable
  
  override val fileStatus: FileStatus
    get() = delegate?.fileStatus ?: FileStatus.NOT_CHANGED
  
  override val filterResults: List<Boolean>
    get() = delegate?.filterResults ?: emptyList()
  
  override val children: List<StructureUiTreeElement>
    get() = delegate?.children ?: emptyList()
  
  override fun equals(other: Any?): Boolean {
    return other is StructureUiTreeElement && id == other.id
  }
  
  override fun hashCode(): Int {
    return id
  }
  
  override fun toString(): String {
    return if (delegate != null) {
      "StructureUiTreeElementWrapper(delegate=$delegate)"
    } else {
      "StructureUiTreeElementWrapper(delegate=null)"
    }
  }
}