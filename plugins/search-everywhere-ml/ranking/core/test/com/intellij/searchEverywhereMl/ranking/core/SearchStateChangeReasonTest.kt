package com.intellij.searchEverywhereMl.ranking.core

import com.intellij.ide.actions.searcheverywhere.SearchRestartReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [SearchStateChangeReason.Companion] class.
 * 
 * Verifies the [SearchStateChangeReason.Companion.fromSearchRestartReason] method
 * which converts [SearchRestartReason] enum values to corresponding [SearchStateChangeReason] values.
 */
class SearchStateChangeReasonTest {
  
  @Test
  fun `search started maps to search start`() {
    val restartReason = SearchRestartReason.SEARCH_STARTED
    val result = SearchStateChangeReason.fromSearchRestartReason(restartReason)
    assertEquals(SearchStateChangeReason.SEARCH_START, result)
  }
  
  @Test
  fun `text changed maps to query change`() {
    val restartReason = SearchRestartReason.TEXT_CHANGED
    val result = SearchStateChangeReason.fromSearchRestartReason(restartReason)
    assertEquals(SearchStateChangeReason.QUERY_CHANGE, result)
  }
  
  @Test
  fun `tab changed maps to tab change`() {
    val restartReason = SearchRestartReason.TAB_CHANGED
    val result = SearchStateChangeReason.fromSearchRestartReason(restartReason)
    assertEquals(SearchStateChangeReason.TAB_CHANGE, result)
  }
  
  @Test
  fun `scope changed maps to scope change`() {
    val restartReason = SearchRestartReason.SCOPE_CHANGED
    val result = SearchStateChangeReason.fromSearchRestartReason(restartReason)
    assertEquals(SearchStateChangeReason.SCOPE_CHANGE, result)
  }
  
  @Test
  fun `exit dumb mode maps to dumb mode exit`() {
    val restartReason = SearchRestartReason.EXIT_DUMB_MODE
    val result = SearchStateChangeReason.fromSearchRestartReason(restartReason)
    assertEquals(SearchStateChangeReason.DUMB_MODE_EXIT, result)
  }
  
  @Test
  fun `text search option changed maps to query matching option change`() {
    val restartReason = SearchRestartReason.TEXT_SEARCH_OPTION_CHANGED
    val result = SearchStateChangeReason.fromSearchRestartReason(restartReason)
    assertEquals(SearchStateChangeReason.QUERY_MATCHING_OPTION_CHANGE, result)
  }
}
