// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.workspace.storage.testEntities.entities

import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityType
import com.intellij.platform.workspace.storage.GeneratedCodeApiVersion
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.WorkspaceEntity
import com.intellij.platform.workspace.storage.annotations.Parent


interface MainEntity : WorkspaceEntity {
  val x: String

  //region generated code
  @GeneratedCodeApiVersion(3)
  interface Builder : WorkspaceEntity.Builder<MainEntity> {
    override var entitySource: EntitySource
    var x: String
  }

  companion object : EntityType<MainEntity, Builder>() {
    @JvmOverloads
    @JvmStatic
    @JvmName("create")
    operator fun invoke(
      x: String,
      entitySource: EntitySource,
      init: (Builder.() -> Unit)? = null,
    ): Builder {
      val builder = builder()
      builder.x = x
      builder.entitySource = entitySource
      init?.invoke(builder)
      return builder
    }
  }
  //endregion

}

//region generated code
fun MutableEntityStorage.modifyMainEntity(
  entity: MainEntity,
  modification: MainEntity.Builder.() -> Unit,
): MainEntity {
  return modifyEntity(MainEntity.Builder::class.java, entity, modification)
}

var MainEntity.Builder.child: AttachedEntity.Builder?
  by WorkspaceEntity.extensionBuilder(AttachedEntity::class.java)
//endregion

interface AttachedEntity : WorkspaceEntity {
  @Parent
  val ref: MainEntity
  val data: String

  //region generated code
  @GeneratedCodeApiVersion(3)
  interface Builder : WorkspaceEntity.Builder<AttachedEntity> {
    override var entitySource: EntitySource
    var ref: MainEntity.Builder
    var data: String
  }

  companion object : EntityType<AttachedEntity, Builder>() {
    @JvmOverloads
    @JvmStatic
    @JvmName("create")
    operator fun invoke(
      data: String,
      entitySource: EntitySource,
      init: (Builder.() -> Unit)? = null,
    ): Builder {
      val builder = builder()
      builder.data = data
      builder.entitySource = entitySource
      init?.invoke(builder)
      return builder
    }
  }
  //endregion

}

//region generated code
fun MutableEntityStorage.modifyAttachedEntity(
  entity: AttachedEntity,
  modification: AttachedEntity.Builder.() -> Unit,
): AttachedEntity {
  return modifyEntity(AttachedEntity.Builder::class.java, entity, modification)
}
//endregion

val MainEntity.child: AttachedEntity?
    by WorkspaceEntity.extension()
