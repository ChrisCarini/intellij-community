// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.ui.table

import com.intellij.vcs.log.*
import org.jetbrains.annotations.ApiStatus

/**
 * Vcs Log commit list.
 */
@ApiStatus.Experimental
interface VcsLogCommitList {
  /**
   * Current commit selection.
   */
  val selection: VcsLogCommitSelection

  /**
   * Commit list model.
   */
  val listModel: VcsLogCommitListModel
}

/**
 * Vcs Log commit list model.
 */
@ApiStatus.Experimental
interface VcsLogCommitListModel {
  /**
   * Corresponding instance of the [VcsLogDataProvider].
   */
  val dataProvider: VcsLogDataProvider

  /**
   * Identifier of the commit at the specified row.
   *
   * @see com.intellij.vcs.log.VcsLogDataProvider.getCommitIndex
   */
  fun getId(row: Int): VcsLogCommitStorageIndex?
}

internal fun VcsLogCommitListModel.getCommitId(row: Int): CommitId? {
  return getId(row)?.let(dataProvider::getCommitId)
}

internal fun VcsLogCommitListModel.getCachedCommitMetadata(row: Int): VcsCommitMetadata? {
  return getId(row)?.let(dataProvider.commitMetadataCache::getCachedData)
}
