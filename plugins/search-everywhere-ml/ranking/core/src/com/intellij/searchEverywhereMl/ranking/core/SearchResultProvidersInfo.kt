package com.intellij.searchEverywhereMl.ranking.core

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereMixedListInfo
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.searchEverywhereMl.SearchEverywhereTab

internal data class SearchResultProvidersInfo(
  val isMixedList: Boolean,
  val providerPriorities: Map<String, Int>,
) {
  companion object {
    val EMPTY = SearchResultProvidersInfo(false, emptyMap())

    fun fromMixedListInfo(mixedListInfo: SearchEverywhereMixedListInfo?): SearchResultProvidersInfo {
      if (mixedListInfo == null) return EMPTY
      return SearchResultProvidersInfo(mixedListInfo.isMixedList, mixedListInfo.contributorPriorities)
    }

    fun forSplitTab(tab: SearchEverywhereTab): SearchResultProvidersInfo {
      if (tab != SearchEverywhereTab.All) return EMPTY
      return SearchResultProvidersInfo(true, createDefaultMixedListPriorities())
    }

    private fun createDefaultMixedListPriorities(): Map<String, Int> {
      val prioritizedProviders = mutableListOf<String>()
      prioritizedProviders.add("com.intellij.ide.actions.searcheverywhere.CalculatorSEContributor")
      prioritizedProviders.add("AutocompletionContributor")
      prioritizedProviders.add("CommandsContributor")
      prioritizedProviders.add("TopHitSEContributor")
      if (AdvancedSettings.getBoolean("search.everywhere.recent.at.top")) {
        prioritizedProviders.add("RecentFilesSEContributor")
      }

      return buildMap {
        prioritizedProviders.forEachIndexed { index, providerId ->
          put(providerId, prioritizedProviders.size - index)
        }
      }
    }
  }
}
