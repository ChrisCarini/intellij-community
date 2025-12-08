// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.impl.uiModel

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.rpc.navigatable
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.vcs.FileStatus
import com.intellij.platform.structureView.impl.dto.StructureViewTreeElementDto
import com.intellij.platform.structureView.impl.dto.toPresentation
import com.intellij.pom.Navigatable

class StructureUiTreeElement(var dto: StructureViewTreeElementDto?, val parent: StructureUiTreeElement?) {
  val id: Int
    get() = dto?.id ?: -1

  val presentation: ItemPresentation
    get() = dto?.presentation?.toPresentation() ?: PresentationData("", "", null, null)

  val speedSearchText: String?
    get() = dto?.speedSearchText

  val alwaysShowPlus: Boolean
    get() = dto?.alwaysShowsPlus ?: false

  val alwaysLeaf: Boolean
    get() = dto?.alwaysLeaf ?: false

  val shouldAutoExpand: Boolean
    get() = dto?.autoExpand ?: false

  val navigatable: Navigatable?
    get() = dto?.navigatable?.navigatable()

  val fileStatus: FileStatus
    get() = FileStatus.NOT_CHANGED

  val filterResults: List<Boolean>
    get() = dto?.filterResults ?: emptyList()


  internal val myChildren = mutableListOf<StructureUiTreeElement>()

  val children: List<StructureUiTreeElement> get() = myChildren

  override fun equals(other: Any?): Boolean {
    return other is StructureUiTreeElement && id == other.id
  }

  override fun hashCode(): Int {
    return id
  }

  override fun toString(): String {
    return "StructureUiTreeElement(dto=$dto)"
  }

  companion object {
    fun StructureViewTreeElementDto.toUiElement(parent: StructureUiTreeElement?): StructureUiTreeElement {
      return StructureUiTreeElement(this, parent)
    }
  }
}