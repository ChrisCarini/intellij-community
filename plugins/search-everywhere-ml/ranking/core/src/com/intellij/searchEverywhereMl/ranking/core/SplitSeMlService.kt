package com.intellij.searchEverywhereMl.ranking.core

import com.intellij.ide.util.scopeChooser.ScopeDescriptor
import com.intellij.ide.util.scopeChooser.ScopesStateService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.currentOrDefaultProject
import com.intellij.platform.searchEverywhere.SeItemData
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.platform.searchEverywhere.frontend.ml.SeMlService
import com.intellij.platform.searchEverywhere.providers.SeEverywhereFilter
import com.intellij.platform.searchEverywhere.providers.target.SeTargetsFilter
import com.intellij.platform.searchEverywhere.withWeight
import com.intellij.searchEverywhereMl.SearchEverywhereTab
import com.intellij.searchEverywhereMl.ranking.core.adapters.SearchResultAdapter
import com.intellij.searchEverywhereMl.ranking.core.adapters.SearchStateChangeReason

internal class SplitSeMlService : SeMlService {
  override val isEnabled: Boolean
    get() = SearchEverywhereMlFacade.isMlEnabled


  override fun onSessionStarted(project: Project?, tabId: String) {
    SearchEverywhereMlFacade.onSessionStarted(project, tabId, isNewSearchEverywhere = true)
  }

  override fun applyMlWeight(seItemData: SeItemData): SeItemData {
    val adapter = SearchResultAdapter.createAdapterFor(seItemData)
    val processedSearchResult = SearchEverywhereMlFacade.processSearchResult(adapter)
                                ?: return seItemData

    if (processedSearchResult.mlProbability != null) {
      val weight = processedSearchResult.mlProbability.toWeight()
      return seItemData.withWeight(weight)
    }
    else {
      return seItemData
    }
  }

  override fun onStateStarted(tabId: String, searchParams: SeParams) {
    val activeSession = checkNotNull(SearchEverywhereMlFacade.activeSession) { "Cannot call onStateStarted without active search session" }
    val project = activeSession.project
    val previousState = activeSession.previousSearchState

    val scopeDescriptor = searchParams.getScopeDescriptorIfExists(currentOrDefaultProject(project))
    val isSearchEverywhere = searchParams.isSearchEverywhere()
    val reason = inferStateChangeReason(previousState, tabId, searchParams, scopeDescriptor)

    SearchEverywhereMlFacade.onStateStarted(tabId, searchParams.inputQuery, reason, scopeDescriptor, isSearchEverywhere)
  }

  override fun onStateFinished(results: List<SeItemData>) {
    SearchEverywhereMlFacade.onStateFinished(results.map { SearchResultAdapter.createAdapterFor(it) })
  }

  override fun onResultsSelected(selectedResults: List<Pair<Int, SeItemData>>) {
    SearchEverywhereMlFacade.onResultsSelected(
      selectedResults.map {
        it.first to SearchResultAdapter.createAdapterFor(it.second)
      }
    )
  }

  override fun onSessionFinished() {
    SearchEverywhereMlFacade.onSessionFinished()
  }

  private fun inferStateChangeReason(
    previousState: SearchEverywhereMLSearchSession.SearchState?,
    tabId: String,
    searchParams: SeParams,
    scopeDescriptor: ScopeDescriptor?,
  ): SearchStateChangeReason {
    if (previousState == null) {
      return SearchStateChangeReason.SEARCH_START
    }

    val tab = SearchEverywhereTab.getById(tabId)

    return when {
      searchParams.inputQuery != previousState.query -> SearchStateChangeReason.QUERY_CHANGE
      scopeDescriptor != previousState.searchScope -> SearchStateChangeReason.SCOPE_CHANGE
      tab != previousState.tab -> SearchStateChangeReason.TAB_CHANGE
      else -> SearchStateChangeReason.QUERY_CHANGE
    }
  }

  private fun SeParams.getScopeDescriptorIfExists(project: Project): ScopeDescriptor? {
    val selectedScopeId = SeTargetsFilter.from(this.filter).selectedScopeId ?: return null
    return ScopesStateService.getInstance(project)
      .getScopesState()
      .getScopeDescriptorById(selectedScopeId)
  }

  private fun SeParams.isSearchEverywhere(): Boolean {
    return SeEverywhereFilter.isEverywhere(this.filter) ?: false
  }
}
