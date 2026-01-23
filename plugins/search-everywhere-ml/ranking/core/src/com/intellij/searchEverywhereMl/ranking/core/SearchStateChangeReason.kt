package com.intellij.searchEverywhereMl.ranking.core

import com.intellij.ide.actions.searcheverywhere.SearchRestartReason

/**
 * This class is an internal equivalent of [com.intellij.ide.actions.searcheverywhere.SearchRestartReason]
 * that is not implemented in the new (split) Search Everywhere.
 *
 * For compatibility and analytical purposes, we want to record this information,
 * so in the new Search Everywhere, we are going to infer this information.
 */
enum class SearchStateChangeReason {
  SEARCH_START,
  QUERY_CHANGE,
  QUERY_MATCHING_OPTION_CHANGE,
  SCOPE_CHANGE,
  TAB_CHANGE,
  DUMB_MODE_EXIT;

  companion object {
    /**
     * Converts values of [SearchRestartReason] (old Search Everywhere implementation)
     * to corresponding [SearchStateChangeReason] (implementation-agnostic) values.
     */
    fun fromSearchRestartReason(restartReason: SearchRestartReason): SearchStateChangeReason {
      return when (restartReason) {
        SearchRestartReason.SEARCH_STARTED -> SEARCH_START
        SearchRestartReason.TEXT_CHANGED -> QUERY_CHANGE
        SearchRestartReason.TAB_CHANGED -> TAB_CHANGE
        SearchRestartReason.SCOPE_CHANGED -> SCOPE_CHANGE
        SearchRestartReason.EXIT_DUMB_MODE -> DUMB_MODE_EXIT
        SearchRestartReason.TEXT_SEARCH_OPTION_CHANGED -> QUERY_MATCHING_OPTION_CHANGE
      }
    }
  }
}