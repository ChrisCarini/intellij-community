// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.backend

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.rpc.weakRpcId
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.newStructureView.StructureViewUtil
import com.intellij.ide.ui.colors.SerializableSimpleTextAttributes
import com.intellij.ide.ui.colors.rpcId
import com.intellij.ide.ui.icons.rpcId
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeActionWithDefaultState
import com.intellij.navigation.ColoredItemPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.LocationPresentation
import com.intellij.openapi.util.PropertyOwner
import com.intellij.platform.structureView.impl.dto.ColoredFragmentDto
import com.intellij.platform.structureView.impl.dto.PresentationDataDto
import com.intellij.platform.structureView.impl.dto.StructureViewTreeElementDto
import com.intellij.platform.structureView.impl.dto.toDto
import com.intellij.platform.structureView.impl.uiModel.StructureTreeAction
import com.intellij.platform.structureView.impl.uiModel.StructureTreeActionImpl
import com.intellij.pom.Navigatable

internal fun StructureViewTreeElement.toDto(id: Int, parentId: Int, autoExpand: Boolean?, alwaysShowsPlus: Boolean?, alwaysLeaf: Boolean?, filterResults: List<Boolean>): StructureViewTreeElementDto {
  val presentation = presentation
  return StructureViewTreeElementDto(
    id,
    parentId,
    StructureViewUtil.getSpeedSearchText(this),
    this.value.hashCode(),
    presentation.toDto(),
    (value as? Navigatable)?.weakRpcId(),
    autoExpand ?: false,
    alwaysShowsPlus ?: false,
    alwaysLeaf ?: false,
    filterResults,
  )
}

internal fun ItemPresentation.toDto(): PresentationDataDto {
  return PresentationDataDto(
    getIcon(false)?.rpcId(),
    presentableText,
    locationString,
    ((this as? ColoredItemPresentation)?.textAttributesKey)?.rpcId(),
    (this as? LocationPresentation)?.locationPrefix,
    (this as? LocationPresentation)?.locationSuffix,
    (this as? PresentationData)?.coloredText?.map {
      ColoredFragmentDto(it.text, it.toolTip, SerializableSimpleTextAttributes(it.attributes.bgColor?.rpcId(),
                                                                               it.attributes.bgColor?.rpcId(),
                                                                               it.attributes.bgColor?.rpcId(),
                                                                               it.attributes.style))
    } ?: emptyList()
  )
}

internal fun Array<Sorter>.toDto(): List<StructureTreeAction> {
  val dto = mutableListOf<StructureTreeAction>()
  for (sorter in this) {
    if (!sorter.isVisible) continue
    when (sorter) {
      Sorter.ALPHA_SORTER -> {
        dto.add(StructureTreeAction.ALPHA_SORTER)
      }
      else -> {
        dto.add(StructureTreeActionImpl(
          StructureTreeAction.Type.SORTER,
          sorter.name,
          false,
          sorter.presentation.toDto(),
          (sorter as? PropertyOwner)?.propertyName ?: sorter.name,
          (sorter as? TreeActionWithDefaultState)?.isEnabledByDefault ?: false
        ))
      }
    }
  }
  return dto
}