package com.intellij.searchEverywhereMl.ranking.core

import com.intellij.searchEverywhereMl.SearchEverywhereTab
import com.intellij.searchEverywhereMl.ranking.core.adapters.SearchStateChangeReason
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@TestApplication
internal class SplitSeMlServiceInferReasonTest {
  private val actionsTabId = SearchEverywhereTab.Actions.tabId
  private val allTabId = SearchEverywhereTab.All.tabId

  @AfterEach
  fun tearDown() {
    SearchEverywhereMlFacade.onSessionFinished()
  }

  @Test
  fun `null previous state returns SEARCH_START`() {
    val reason = SplitSeMlService.inferStateChangeReason(
      previousState = null,
      tabId = actionsTabId,
      inputQuery = "test",
      scopeDescriptor = null,
    )
    assertEquals(SearchStateChangeReason.SEARCH_START, reason)
  }

  @Test
  fun `different query returns QUERY_CHANGE`() {
    val session = SearchEverywhereMLSearchSession.createNext(null)
    session.onStateStarted(actionsTabId, "old query", SearchStateChangeReason.SEARCH_START, null, false)
    val previousState = session.activeState!!

    val reason = SplitSeMlService.inferStateChangeReason(
      previousState = previousState,
      tabId = actionsTabId,
      inputQuery = "new query",
      scopeDescriptor = null,
    )
    assertEquals(SearchStateChangeReason.QUERY_CHANGE, reason)
  }

  @Test
  fun `same query different scope returns SCOPE_CHANGE`() {
    val session = SearchEverywhereMLSearchSession.createNext(null)
    session.onStateStarted(actionsTabId, "query", SearchStateChangeReason.SEARCH_START, null, false)
    val previousState = session.activeState!!

    val mockScope = com.intellij.ide.util.scopeChooser.ScopeDescriptor(
      com.intellij.psi.search.GlobalSearchScope.EMPTY_SCOPE
    )

    val reason = SplitSeMlService.inferStateChangeReason(
      previousState = previousState,
      tabId = actionsTabId,
      inputQuery = "query",
      scopeDescriptor = mockScope,
    )
    assertEquals(SearchStateChangeReason.SCOPE_CHANGE, reason)
  }

  @Test
  fun `same query same scope different tab returns TAB_CHANGE`() {
    val session = SearchEverywhereMLSearchSession.createNext(null)
    session.onStateStarted(actionsTabId, "query", SearchStateChangeReason.SEARCH_START, null, false)
    val previousState = session.activeState!!

    val reason = SplitSeMlService.inferStateChangeReason(
      previousState = previousState,
      tabId = allTabId,
      inputQuery = "query",
      scopeDescriptor = null,
    )
    assertEquals(SearchStateChangeReason.TAB_CHANGE, reason)
  }

  @Test
  fun `all same returns QUERY_CHANGE fallback`() {
    val session = SearchEverywhereMLSearchSession.createNext(null)
    session.onStateStarted(actionsTabId, "query", SearchStateChangeReason.SEARCH_START, null, false)
    val previousState = session.activeState!!

    val reason = SplitSeMlService.inferStateChangeReason(
      previousState = previousState,
      tabId = actionsTabId,
      inputQuery = "query",
      scopeDescriptor = null,
    )
    assertEquals(SearchStateChangeReason.QUERY_CHANGE, reason)
  }
}
