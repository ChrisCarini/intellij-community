// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.structureView.impl.uiModel

import com.intellij.ide.rpc.ShortcutId
import com.intellij.ide.rpc.shortcut
import com.intellij.ide.util.treeView.smartTree.ActionPresentation
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.util.PropertyOwner
import com.intellij.platform.structureView.impl.dto.StructureViewTreeElementDto
import com.intellij.platform.structureView.impl.dto.TreeActionPresentationDto
import com.intellij.platform.structureView.impl.dto.toDto
import com.intellij.platform.structureView.impl.dto.toPresentation
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls

@ApiStatus.Internal
@Serializable
sealed interface StructureTreeAction: PropertyOwner {
  val actionType: Type
  val name: @NonNls String
  val isReverted: Boolean
  val presentation: ActionPresentation
  val isEnabledByDefault: Boolean

  companion object {
    val ALPHA_SORTER: StructureTreeAction = StructureTreeActionImpl(Type.SORTER,
                                                                    Sorter.ALPHA_SORTER.name,
                                                                    false,
                                                                    Sorter.ALPHA_SORTER.presentation.toDto(),
                                                                    Sorter.ALPHA_SORTER.name,
                                                                    true)
  }

  enum class Type {
    GROUP,
    SORTER,
    FILTER
  }
}

@ApiStatus.NonExtendable
interface CheckboxTreeAction : StructureTreeAction {
  val shortcutsIds: Array<ShortcutId>?
  val actionIdForShortcut: String?
  val checkboxText: @Nls String

  val shortcuts: Array<Shortcut>?
    get() = shortcutsIds?.mapNotNull { it.shortcut() }?.toTypedArray()
}

@Serializable
open class StructureTreeActionImpl(override val actionType: StructureTreeAction.Type,
                                   override val name: String,
                                   override val isReverted: Boolean,
                                   private val presentationDto: TreeActionPresentationDto,
                                   private val myPropertyName: String,
                                   override val isEnabledByDefault: Boolean) : StructureTreeAction {
  @Transient
  override val presentation: ActionPresentation = presentationDto.toPresentation()

  override fun getPropertyName(): String = myPropertyName
}

@Serializable
class NodeProviderTreeAction(
  override val actionType: StructureTreeAction.Type,
  override val name: String,
  val presentationDto: TreeActionPresentationDto,
  override val isReverted: Boolean,
  override val isEnabledByDefault: Boolean,
  override val shortcutsIds: Array<ShortcutId>?,
  override val actionIdForShortcut: String?,
  override val checkboxText: @Nls String,
  private val myPropertyName: String,
  val nodesDto: List<StructureViewTreeElementDto>,
) : CheckboxTreeAction {

  @Transient
  override val presentation: ActionPresentation = presentationDto.toPresentation()

  override fun getPropertyName(): String = myPropertyName

  override fun toString(): String {
    return "StructureTreeAction{dto=$presentationDto}"
  }
}

@Serializable
class FilterTreeAction(
  val order: Int,
  override val actionType: StructureTreeAction.Type,
  override val name: String,
  val presentationDto: TreeActionPresentationDto,
  override val isReverted: Boolean,
  override val isEnabledByDefault: Boolean,
  override val shortcutsIds: Array<ShortcutId>?,
  override val actionIdForShortcut: String?,
  override val checkboxText: @Nls String,
  private val myPropertyName: String,
) : CheckboxTreeAction {
  @Transient
  override val presentation: ActionPresentation = presentationDto.toPresentation()

  override fun getPropertyName(): String = myPropertyName

  fun isVisible(element: StructureUiTreeElement): Boolean {
    return element.filterResults.getOrNull(order) ?: true
  }
}