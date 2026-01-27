// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:OptIn(IntellijInternalApi::class)

package com.intellij.searchEverywhereMl.ranking.core

import ai.grazie.emb.FloatTextEmbedding
import com.intellij.concurrency.ConcurrentCollectionFactory
import com.intellij.ide.actions.searcheverywhere.*
import com.intellij.ide.util.scopeChooser.ScopeDescriptor
import com.intellij.internal.statistic.eventLog.events.EventPair
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IntellijInternalApi
import com.intellij.searchEverywhereMl.SearchEverywhereTab
import com.intellij.searchEverywhereMl.TextEmbeddingProvider
import com.intellij.searchEverywhereMl.isLoggingEnabled
import com.intellij.searchEverywhereMl.isTabWithMlRanking
import com.intellij.searchEverywhereMl.ranking.core.adapters.MlProbability
import com.intellij.searchEverywhereMl.ranking.core.adapters.SearchResultAdapter
import com.intellij.searchEverywhereMl.ranking.core.adapters.SessionWideId
import com.intellij.searchEverywhereMl.ranking.core.adapters.SearchResultProviderAdapter
import com.intellij.searchEverywhereMl.ranking.core.adapters.StateLocalId
import com.intellij.searchEverywhereMl.ranking.core.features.*
import com.intellij.searchEverywhereMl.ranking.core.features.SearchEverywhereElementFeaturesProvider.Companion.ML_SCORE_KEY
import com.intellij.searchEverywhereMl.ranking.core.features.statistician.SearchEverywhereStatisticianService
import com.intellij.searchEverywhereMl.ranking.core.features.statistician.increaseProvidersUseCount
import com.intellij.searchEverywhereMl.ranking.core.id.MissingKeyProviderCollector
import com.intellij.searchEverywhereMl.ranking.core.id.SearchEverywhereMlOrderedItemIdProvider
import com.intellij.searchEverywhereMl.ranking.core.model.SearchEverywhereModelProvider
import com.intellij.searchEverywhereMl.ranking.core.model.SearchEverywhereRankingModel
import com.intellij.searchEverywhereMl.ranking.core.performance.PerformanceTracker
import com.intellij.searchEverywhereMl.ranking.core.utils.convertNameToNaturalLanguage
import com.intellij.util.applyIf
import com.intellij.util.concurrency.NonUrgentExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class SearchEverywhereMLSearchSession private constructor(
  val project: Project?,
  val sessionId: Int,
) {
  val sessionWideIdProvider =
    SearchEverywhereMlOrderedItemIdProvider { MissingKeyProviderCollector.addMissingProviderForClass(it::class.java) }

  val sessionStartTime: Long = System.currentTimeMillis()
  private val providersCache = FeaturesProviderCacheDataProvider().getDataToCache(project)
  private val modelProviderWithCache: SearchEverywhereModelProvider = SearchEverywhereModelProvider()
  private val embeddingCache = ConcurrentCollectionFactory.createConcurrentMap<String, FloatTextEmbedding>()

  // context features are calculated once per Search Everywhere session
  val cachedContextInfo: SearchEverywhereMLContextInfo = SearchEverywhereMLContextInfo(project)

  // search state is updated on each typing, tab or setting change
  // element features & ML score are also re-calculated on each typing because some of them might change, e.g. matching degree
  private val currentSearchState: AtomicReference<SearchState> = AtomicReference<SearchState>()

  private val performanceTracker = PerformanceTracker()

  fun onSessionStarted(tabId: String) {
    val tab = SearchEverywhereTab.getById(tabId)
    SearchEverywhereMLStatisticsCollector.onSessionStarted(project, sessionId, tab, sessionStartTime, cachedContextInfo.features)
  }

  fun onSearchRestart(
    reason: SearchStateChangeReason,
    tabId: String,
    searchQuery: String,
    rawSearchResults: List<SearchResultAdapter.Raw>,
    searchScope: ScopeDescriptor?,
    isSearchEverywhere: Boolean,
  ) {
    // Note - the searchResults are associated with the previous search state.
    // For the first search the searchResults list will always be empty.
    // This does not "reflect the actual state". For instance, in the "All" tab, the actual list may be prepopulated.
    // For this reason it is important NOT to associate the searchResults with the current search state,
    // but with the previous one.

    val tab = SearchEverywhereTab.getById(tabId)
    val prevTimeToResult = performanceTracker.timeElapsed

    val prevState = currentSearchState.getAndUpdate { prevState ->
      val stateChangeReason = if (prevState == null) SearchStateChangeReason.SEARCH_START else reason
      val nextSearchIndex = (prevState?.index ?: 0) + 1
      performanceTracker.start()

      SearchState(nextSearchIndex, tab, searchScope, isSearchEverywhere, stateChangeReason, searchQuery)
    }

    if (prevState != null && prevState.tab.isLoggingEnabled()) {
      val processedSearchResults = rawSearchResults.map { prevState.getProcessedSearchResultById(it.stateLocalId) }
      SearchEverywhereMLStatisticsCollector.onSearchRestarted(project, this, prevState, processedSearchResults, prevTimeToResult)
    }
  }

  fun onItemSelected(
    indexes: IntArray, selectedItems: List<Any>,
    searchResults: List<SearchResultAdapter.Raw>,
  ) {
    val state = getCurrentSearchState() ?: return
    if (!state.tab.isLoggingEnabled()) return

    val statisticianService = service<SearchEverywhereStatisticianService>()
    selectedItems.forEach { statisticianService.increaseUseCount(it) }

    if (state.tab == SearchEverywhereTab.All) {
      searchResults
        .slice(indexes.asIterable())
        .forEach { increaseProvidersUseCount(it.provider.id) }
    }

    indexes.forEach { selectedIndex ->
      SearchEverywhereMLStatisticsCollector.onItemSelected(project, sessionId, state.index, selectedIndex)
    }
  }

  fun onSearchFinished(rawSearchResults: List<SearchResultAdapter.Raw>) {
    val state = getCurrentSearchState() ?: return

    val sessionEndTime = System.currentTimeMillis()
    val sessionDuration = (sessionEndTime - sessionStartTime).toInt()

    if (state.tab.isLoggingEnabled()) {
      // "flush" the previous search restarted event
      val processedSearchResults = rawSearchResults.map { state.getProcessedSearchResultById(it.stateLocalId) }
      SearchEverywhereMLStatisticsCollector.onSearchRestarted(project, this, state, processedSearchResults, performanceTracker.timeElapsed)
    }

    SearchEverywhereMLStatisticsCollector.onSessionFinished(project, sessionId, state.tab, sessionDuration)

    MissingKeyProviderCollector.report(sessionId)
  }

  fun notifySearchResultsUpdated() {
    performanceTracker.stop()
  }

  fun getCurrentSearchState(): SearchState? = currentSearchState.get()

  fun getSearchQueryEmbedding(searchQuery: String, split: Boolean): FloatTextEmbedding? {
    return embeddingCache[searchQuery]
           ?: TextEmbeddingProvider.getProvider()?.embed(if (split) convertNameToNaturalLanguage(searchQuery) else searchQuery)
             ?.also { embeddingCache[searchQuery] = it }
  }

  inner class SearchState(
    val index: Int,
    val tab: SearchEverywhereTab,
    val searchScope: ScopeDescriptor?,
    val isSearchEverywhere: Boolean,
    val searchStateChangeReason: SearchStateChangeReason,
    val query: String,
  ) {
    val project: Project?
      get() = this@SearchEverywhereMLSearchSession.project

    val searchStateFeatures = SearchEverywhereStateFeaturesProvider.getFeatures(this)

    val orderByMl: Boolean
      get() {
        if (!tab.isTabWithMlRanking()) {
          return false
        }

        if (tab == SearchEverywhereTab.All && query.isEmpty()) {
          return false
        }

        if (tab == SearchEverywhereTab.Actions && query.isEmpty()) {
          // Don't sort recently used actions which appear on an empty query
          return false
        }

        return tab.isMlRankingEnabled
      }

    private val model: SearchEverywhereRankingModel by lazy { modelProviderWithCache.getModel(tab as SearchEverywhereTab.TabWithMlRanking) }

    private val searchResultCache: MutableMap<StateLocalId, SearchResultAdapter.Processed> = mutableMapOf()

    fun processSearchResult(
      searchResult: SearchResultAdapter.Raw,
      correction: SearchEverywhereSpellCheckResult = SearchEverywhereSpellCheckResult.NoCorrection,
    ): SearchResultAdapter.Processed {
      return searchResult
        .toProcessed()
        .computeMlFeatures(correction)
        .computeMlProbabilityIfEnabled()
        .tryComputeId()
        .also {
          searchResultCache[it.stateLocalId] = it
        }
    }

    fun getProcessedSearchResultById(id: StateLocalId): SearchResultAdapter.Processed {
      return searchResultCache.getValue(id)
    }

    private fun getAllFeatures(
      contextFeatures: List<EventPair<*>>,
      elementFeatures: List<EventPair<*>>,
      contributorFeatures: List<EventPair<*>>,
    ): Map<String, Any?> {
      return (contextFeatures + elementFeatures + searchStateFeatures + contributorFeatures)
        .associate { it.field.name to it.data }
    }

    private fun getForContributor(contributorId: String): SearchEverywhereRankingModel {
      val tab = when (contributorId) {
        ActionSearchEverywhereContributor::class.java.simpleName -> SearchEverywhereTab.Actions
        FileSearchEverywhereContributor::class.java.simpleName, RecentFilesSEContributor::class.java.simpleName -> SearchEverywhereTab.Files
        ClassSearchEverywhereContributor::class.java.simpleName -> SearchEverywhereTab.Classes
        else -> throw IllegalArgumentException("Unsupported provider: $contributorId")
      }

      return modelProviderWithCache.getModel(tab)
    }

    private fun shouldCalculateMlWeight(searchResult: SearchResultAdapter): Boolean {
      if (!orderByMl) {
        return false
      }

      if (searchResult.isSemantic) {
        // Do not calculate machine learning weight for semantic items until the ranking models know how to treat them
        return false
      }

      return true
    }


    fun getContributorFeatures(provider: SearchResultProviderAdapter): List<EventPair<*>> {
      return SearchEverywhereContributorFeaturesProvider.getFeatures(provider, sessionStartTime)
    }

    private fun SearchResultAdapter.Raw.toProcessed(): SearchResultAdapter.Processed {
      return SearchResultAdapter.Processed(this, null, null, null)
    }

    private fun SearchResultAdapter.Processed.computeMlFeatures(correction: SearchEverywhereSpellCheckResult = SearchEverywhereSpellCheckResult.NoCorrection): SearchResultAdapter.Processed {
      val mlFeatures = SearchEverywhereElementFeaturesProvider.getFeatureProvidersForContributor(provider.id)
        .flatMap { featuresProvider ->
          featuresProvider.getElementFeatures(getRawItem(),
                                              sessionStartTime,
                                              query,
                                              originalWeight,
                                              providersCache,
                                              correction)
        }
        .applyIf(tab == SearchEverywhereTab.All) {
          val contributorFeatures = getContributorFeatures(provider)
          val mlScore = getElementMLScoreForAllTab(provider.id, cachedContextInfo.features, this, contributorFeatures)
          if (mlScore == null) {
            return@applyIf this
          }
          else {
            return@applyIf this + listOf(ML_SCORE_KEY.with(mlScore))
          }
        }

      return this.copy(mlFeatures = mlFeatures)
    }

    /**
     * Computes the ML score for an element based on its features and the contributor's model in All tab
     * where elements from different contributors are included in the search results.
     * This function should only be called for All tab, and it will throw an exception if called with a different tabId.
     * If there is no ML model for the given element, the function will return null.
     * @param contributorId The ID of the contributor that provided the element.
     * @param contextFeatures The list of context-related features.
     * @param elementFeatures The list of element-related features.
     * @param contributorFeatures The list of contributor-related features.
     */
    private fun getElementMLScoreForAllTab(contributorId: String,
                                           contextFeatures: List<EventPair<*>>,
                                           elementFeatures: List<EventPair<*>>,
                                           contributorFeatures: List<EventPair<*>>): Double? {
      check(tab == SearchEverywhereTab.All) { "This function should only be called in the All tab" }

      return try {
        val features = getAllFeatures(contextFeatures, elementFeatures, contributorFeatures)
        val model = getForContributor(contributorId)
        model.predict(features)
      }
      catch (e: IllegalArgumentException) {
        null
      }
    }

    private fun SearchResultAdapter.Processed.computeMlProbabilityIfEnabled(): SearchResultAdapter.Processed {
      if (!shouldCalculateMlWeight(this)) {
        return this
      }

      val mlFeatures = requireNotNull(mlFeatures) { "ML Probability cannot be calculated when feature list is null " }

      val features = getAllFeatures(cachedContextInfo.features,
                                    mlFeatures,
                                    getContributorFeatures(provider))
      val probability = model.predict(features)
      return this.copy(mlProbability = MlProbability(probability))
    }

    private fun SearchResultAdapter.Processed.tryComputeId(): SearchResultAdapter.Processed {
      val id = ReadAction.compute<Int?, Nothing> { sessionWideIdProvider.getId(this.getRawItem()) }
      return if (id != null) {
          this.copy(sessionWideId = SessionWideId(id))
      }
      else {
          this
      }
    }
  }

  companion object {
    private val sessionIdCounter: AtomicInteger = AtomicInteger(0)

    fun createNext(project: Project?): SearchEverywhereMLSearchSession {
      return SearchEverywhereMLSearchSession(project, sessionIdCounter.incrementAndGet())
    }
  }
}

internal class SearchEverywhereMLContextInfo(project: Project?) {
  val features: List<EventPair<*>> by lazy {
    SearchEverywhereContextFeaturesProvider().getContextFeatures(project)
  }

  init {
    NonUrgentExecutor.getInstance().execute {
      features  // We don't care about the value, we just want the features to be computed
    }
  }
}