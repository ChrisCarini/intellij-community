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
import com.intellij.searchEverywhereMl.ranking.core.adapters.SearchResultAdapter
import com.intellij.searchEverywhereMl.ranking.core.adapters.toSearchStateChangeReason
import org.jetbrains.annotations.ApiStatus
import java.util.*


@ApiStatus.Internal
class SearchEverywhereMlRankingService : SearchEverywhereMlService {
  override fun isEnabled(): Boolean {
    return SearchEverywhereMlFacade.isMlEnabled
  }

  override fun onSessionStarted(project: Project?, tabId: String) {
    SearchEverywhereMlFacade.onSessionStarted(project, tabId, isNewSearchEverywhere = false)
  }

  override fun createFoundElementInfo(contributor: SearchEverywhereContributor<*>,
                                      element: Any,
                                      priority: Int,
                                      correction: SearchEverywhereSpellCheckResult): SearchEverywhereFoundElementInfo {
    val elementInfo = SearchEverywhereFoundElementInfo(UUID.randomUUID().toString(), element, priority, contributor, correction)
    val searchResultAdapter = SearchResultAdapter.createAdapterFor(elementInfo)

    val processedSearchResult = SearchEverywhereMlFacade.processSearchResult(searchResultAdapter)
                                ?: return elementInfo

    if (processedSearchResult.mlProbability != null) {
      val weight = processedSearchResult.mlProbability.toWeight()
      // TODO - Handle actions abbreviations!!!
      return elementInfo.withPriority(weight)
    } else {
      return elementInfo
    }
  }

  override fun onStateStarted(
    tabId: String,
    reason: SearchRestartReason,
    searchQuery: String,
    searchScope: ScopeDescriptor?,
    isSearchEverywhere: Boolean,
  ) {
    SearchEverywhereMlFacade.onStateStarted(tabId, searchQuery, reason.toSearchStateChangeReason(), searchScope, isSearchEverywhere)
  }

  override fun onStateFinished(results: List<SearchEverywhereFoundElementInfo>) {
    SearchEverywhereMlFacade.onStateFinished(results.toAdapter())
  }

  override fun onItemSelected(tabId: String, indexes: IntArray, selectedItems: List<Any>,
                              searchResults: List<SearchEverywhereFoundElementInfo>,
                              query: String) {
    val selectedItems = searchResults
      .mapIndexed { index, info ->  index to SearchResultAdapter.createAdapterFor(info) }
      .slice(indexes.toList())

    SearchEverywhereMlFacade.onResultsSelected(selectedItems)
  }

  override fun onSessionFinished() {
    SearchEverywhereMlFacade.onSessionFinished()
  }

  override fun notifySearchResultsUpdated() {
    // TODO - do we still need it or is state finished enough?
  }

  override fun onDialogClose() {
    SearchEverywhereMlFacade.onSessionFinished()
  }

  override fun getExperimentVersion(): Int {
    return SearchEverywhereMlFacade.experimentVersion
  }

  override fun getExperimentGroup(): Int {
    return SearchEverywhereMlFacade.experimentGroup
  }

  private fun List<SearchEverywhereFoundElementInfo>.toAdapter(): List<SearchResultAdapter.Raw> {
    return this.map {
      SearchResultAdapter.createAdapterFor(it)
    }
  }
}