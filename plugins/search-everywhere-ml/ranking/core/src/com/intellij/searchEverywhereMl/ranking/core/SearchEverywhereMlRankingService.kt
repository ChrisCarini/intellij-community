// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.searchEverywhereMl.ranking.core

import com.intellij.ide.actions.searcheverywhere.PSIPresentationBgRendererWrapper
import com.intellij.ide.actions.searcheverywhere.SEListSelectionTracker
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorWrapper
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereMixedListInfo
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereMlService
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereSpellCheckResult
import com.intellij.ide.actions.searcheverywhere.SearchListModel
import com.intellij.ide.actions.searcheverywhere.SearchListener
import com.intellij.ide.actions.searcheverywhere.SearchRestartReason
import com.intellij.ide.actions.searcheverywhere.SemanticSearchEverywhereContributor
import com.intellij.ide.util.scopeChooser.ScopeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.searchEverywhereMl.SearchEverywhereMlExperiment
import com.intellij.searchEverywhereMl.SearchEverywhereTab
import com.intellij.searchEverywhereMl.isTabWithMlRanking
import com.intellij.searchEverywhereMl.ranking.core.adapters.SearchResultAdapter
import org.jetbrains.annotations.ApiStatus
import java.util.*
import java.util.concurrent.atomic.AtomicReference

internal val searchEverywhereMlRankingService: SearchEverywhereMlRankingService?
  get() = SearchEverywhereMlService.EP_NAME.findExtensionOrFail(SearchEverywhereMlRankingService::class.java).takeIf { it.isEnabled() }

@ApiStatus.Internal
class SearchEverywhereMlRankingService : SearchEverywhereMlService {
  private var activeSession: AtomicReference<SearchEverywhereMLSearchSession?> = AtomicReference()

  override fun isEnabled(): Boolean {
    return SearchEverywhereTab.tabsWithLogging.any { it.isTabWithMlRanking() && it.isMlRankingEnabled }
           || SearchEverywhereMlExperiment.isAllowed
  }


  internal fun getCurrentSession(): SearchEverywhereMLSearchSession? {
    if (isEnabled()) {
      return activeSession.get()
    }
    return null
  }

  override fun onSessionStarted(project: Project?, tabId: String) {
    if (isEnabled()) {
      activeSession.updateAndGet {
        SearchEverywhereMLSearchSession.createNext(project)
      }!!.onSessionStarted(tabId)
    }
  }

  override fun createFoundElementInfo(contributor: SearchEverywhereContributor<*>,
                                      element: Any,
                                      priority: Int,
                                      correction: SearchEverywhereSpellCheckResult): SearchEverywhereFoundElementInfo {
    val elementInfo = SearchEverywhereFoundElementInfo(UUID.randomUUID().toString(), element, priority, contributor, correction)

    if (!isEnabled()) return elementInfo
    val session = getCurrentSession() ?: return elementInfo
    val state = session.getCurrentSearchState() ?: return elementInfo

    val searchResultAdapter = SearchResultAdapter.createAdapterFor(elementInfo)
    val processedSearchResult = state.processSearchResult(searchResultAdapter, correction)

    if (processedSearchResult.mlProbability != null) {
      return elementInfo.withPriority(processedSearchResult.mlProbability.toWeight())
    } else {
      return elementInfo
    }
  }

  override fun onSearchRestart(
    tabId: String,
    reason: SearchRestartReason,
    searchQuery: String,
    searchResults: List<SearchEverywhereFoundElementInfo>,
    searchScope: ScopeDescriptor?,
    isSearchEverywhere: Boolean
  ) {
    if (!isEnabled()) return

    getCurrentSession()?.onSearchRestart(
      SearchStateChangeReason.fromSearchRestartReason(reason), tabId, searchQuery, searchResults.toAdapter(),
      searchScope, isSearchEverywhere
    )
  }

  override fun onItemSelected(tabId: String, indexes: IntArray, selectedItems: List<Any>,
                              searchResults: List<SearchEverywhereFoundElementInfo>,
                              query: String) {
    getCurrentSession()?.onItemSelected(indexes, selectedItems, searchResults.toAdapter())
  }

  override fun onSearchFinished(searchResults: List<SearchEverywhereFoundElementInfo>) {
    getCurrentSession()?.onSearchFinished(searchResults.toAdapter())
  }

  override fun notifySearchResultsUpdated() {
    getCurrentSession()?.notifySearchResultsUpdated()
  }

  override fun onDialogClose() {
    activeSession.updateAndGet { null }
  }

  override fun getExperimentVersion(): Int = SearchEverywhereMlExperiment.VERSION

  override fun getExperimentGroup(): Int = SearchEverywhereMlExperiment.experimentGroup

  private fun List<SearchEverywhereFoundElementInfo>.toAdapter(): List<SearchResultAdapter.Raw> {
    return this.map {
      SearchResultAdapter.createAdapterFor(it)
    }
  }
}