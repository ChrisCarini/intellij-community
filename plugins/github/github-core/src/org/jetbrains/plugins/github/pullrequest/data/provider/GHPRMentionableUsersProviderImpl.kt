// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.pullrequest.data.provider

import com.intellij.collaboration.async.BatchesLoader
import com.intellij.platform.util.coroutines.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jetbrains.plugins.github.api.data.GHUser
import org.jetbrains.plugins.github.pullrequest.data.service.GHPRRepositoryDataService
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds


@OptIn(ExperimentalCoroutinesApi::class)
internal class GHPRMentionableUsersProviderImpl(
  parentCs: CoroutineScope,
  repositoryDataService: GHPRRepositoryDataService,
) : GHPRMentionableUsersProvider {

  private val cs = parentCs.childScope(javaClass.name)

  private val mentionableUsersBatchesLoader = BatchesLoader(cs, repositoryDataService.mentionableUsersBatchesFlow())
  private var clearMentionableUsersJob = AtomicReference<Job?>(null)

  /**
   * A repo can have thousands of mentionable which are rarely needed, so let's clean it up after some time
   */
  private fun scheduleMentionableCleanUp() {
    val newJob = cs.launch {
      delay(60.seconds)
      mentionableUsersBatchesLoader.cancel()
    }
    clearMentionableUsersJob.getAndSet(newJob)?.cancel()
  }

  override fun getMentionableUsersBatches(): Flow<List<GHUser>> {
    scheduleMentionableCleanUp()
    return mentionableUsersBatchesLoader.getBatches()
  }
}