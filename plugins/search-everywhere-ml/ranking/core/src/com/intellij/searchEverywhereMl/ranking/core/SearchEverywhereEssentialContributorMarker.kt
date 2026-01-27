@file:OptIn(IntellijInternalApi::class)

package com.intellij.searchEverywhereMl.ranking.core

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereEssentialContributorMarker
import com.intellij.internal.statistic.eventLog.events.EventPair
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.IntellijInternalApi
import com.intellij.searchEverywhereMl.SearchEverywhereTab
import com.intellij.searchEverywhereMl.isEssentialContributorPredictionExperiment
import com.intellij.searchEverywhereMl.ranking.core.adapters.SearchResultProviderAdapter
import com.intellij.searchEverywhereMl.ranking.core.model.CatBoostModelFactory
import com.intellij.searchEverywhereMl.ranking.core.model.SearchEverywhereCatBoostBinaryClassifierModel
import java.util.WeakHashMap


/**
 * Marker that uses machine learning to predict what contributors are essential
 */
internal class SearchEverywhereEssentialContributorMlMarker : SearchEverywhereEssentialContributorMarker {
  companion object {
    private const val MODEL_DIR = "ec_model_exp"
    private const val RESOURCE_DIR = "ec_features_exp"
    const val TRUE_THRESHOLD = 0.4
  }

  private val model: SearchEverywhereCatBoostBinaryClassifierModel = CatBoostModelFactory()
    .withModelDirectory(MODEL_DIR)
    .withResourceDirectory(RESOURCE_DIR)
    .buildBinaryClassifier(TRUE_THRESHOLD)

  /**
   * A cache that stores predicted probabilities for contributors in various search states.
   * Realistically - we are only interested in the last session, thus we are going to use a weak map,
   * so that past sessions and their related predicted probabilities can be garbage-collected.

   * The key is a `SearchState` object that represents the state of a search session.
   * The value is a map associating individual `SearchResultProviderAdapter` with
   * their predicted probabilities (`Float`) for being considered essential in the current search state.
   */
  private val contributorPredictionCache = WeakHashMap<SearchEverywhereMLSearchSession.SearchState, MutableMap<SearchResultProviderAdapter, Float>>()

  override fun isAvailable(): Boolean {
    return isActiveExperiment() && isSearchStateActive()
  }

  private fun isActiveExperiment(): Boolean {
    return SearchEverywhereTab.All.isEssentialContributorPredictionExperiment
  }

  private fun isSearchStateActive(): Boolean {
    try {
      val rankingService = checkNotNull(searchEverywhereMlRankingService) { "Search Everywhere Ranking Service is null" }
      val searchSession = checkNotNull(rankingService.getCurrentSession()) { "Search Everywhere Search Session is null" }
      checkNotNull(searchSession.getCurrentSearchState()) { "Search Everywhere Search State is null" }

      // Search state is active
      return true
    } catch (e: IllegalStateException) {
      thisLogger().debug(e)
      return false
    }
  }

  private fun computeProbability(provider: SearchResultProviderAdapter): Float {
    val features = getFeatures(provider).associate { it.field.name to it.data }
    return model.predict(features).toFloat()
  }

  override fun isContributorEssential(contributor: SearchEverywhereContributor<*>): Boolean {
    val proba = getContributorEssentialPrediction(SearchResultProviderAdapter.createAdapterFor(contributor))
    return proba >= TRUE_THRESHOLD
  }

  internal fun getContributorEssentialPrediction(provider: SearchResultProviderAdapter,
                                                 searchState: SearchEverywhereMLSearchSession.SearchState = getSearchState()): Float {
    val cache = contributorPredictionCache.getOrPut(searchState) { hashMapOf() }
    return cache.getOrPut(provider) {
      computeProbability(provider).also { probability ->
        thisLogger().debug("Predicted probability of ${provider.id} is $probability")
      }
    }
  }

  fun getCachedPredictionsForState(searchState: SearchEverywhereMLSearchSession.SearchState): Map<SearchResultProviderAdapter, Float> {
    return contributorPredictionCache[searchState]?.toMap() ?: emptyMap()
  }

  private fun getFeatures(provider: SearchResultProviderAdapter): List<EventPair<*>> {
    val searchSession = getSearchSession()
    val searchState = getSearchState()

    val sessionContextFeatures = searchSession.cachedContextInfo.features
    val stateFeatures = searchState.searchStateFeatures
    val contributorFeatures = searchState.getContributorFeatures(provider)

    return sessionContextFeatures + stateFeatures + contributorFeatures
  }

  private fun getSearchSession(): SearchEverywhereMLSearchSession {
    val rankingService = checkNotNull(searchEverywhereMlRankingService)
    return checkNotNull(rankingService.getCurrentSession())
  }

  private fun getSearchState(): SearchEverywhereMLSearchSession.SearchState {
    val searchSession = getSearchSession()
    return checkNotNull(searchSession.getCurrentSearchState())
  }
}
