package com.intellij.searchEverywhereMl.ranking.core.adapters

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo
import com.intellij.internal.statistic.eventLog.events.EventPair
import com.intellij.searchEverywhereMl.ranking.core.MockSearchEverywhereContributor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchResultAdapterTest {
  @Test
  fun `MlProbability toWeight converts 0 to 0`() {
    assertEquals(0, MlProbability(0.0).toWeight())
  }

  @Test
  fun `MlProbability toWeight converts 1 to 10000`() {
    assertEquals(10000, MlProbability(1.0).toWeight())
  }

  @Test
  fun `MlProbability toWeight converts 0_5 to 5000`() {
    assertEquals(5000, MlProbability(0.5).toWeight())
  }

  @Test
  fun `MlProbability toWeight truncates not rounds`() {
    assertEquals(1234, MlProbability(0.12349).toWeight())
  }

  @Test
  fun `MlProbability toWeight handles small values`() {
    assertEquals(1, MlProbability(0.0001).toWeight())
  }

  @Test
  fun `StateLocalId preserves its value`() {
    val id = StateLocalId("test-id-123")
    assertEquals("test-id-123", id.value)
  }

  @Test
  fun `SessionWideId preserves its value`() {
    val id = SessionWideId(42)
    assertEquals(42, id.value)
  }

  @Test
  fun `legacy adapter has correct provider id`() {
    val contributor = MockSearchEverywhereContributor("myProviderId")
    val info = SearchEverywhereFoundElementInfo("uuid-1", "element", 100, contributor)
    val adapter = SearchResultAdapter.createAdapterFor(info)

    assertEquals("myProviderId", adapter.provider.id)
  }

  @Test
  fun `legacy adapter has correct weight`() {
    val contributor = MockSearchEverywhereContributor("id")
    val info = SearchEverywhereFoundElementInfo("uuid-1", "element", 42, contributor)
    val adapter = SearchResultAdapter.createAdapterFor(info)

    assertEquals(42, adapter.originalWeight)
  }

  @Test
  fun `legacy adapter has correct stateLocalId`() {
    val contributor = MockSearchEverywhereContributor("id")
    val info = SearchEverywhereFoundElementInfo("my-uuid-value", "element", 100, contributor)
    val adapter = SearchResultAdapter.createAdapterFor(info)

    assertEquals("my-uuid-value", adapter.stateLocalId.value)
  }

  @Test
  fun `legacy adapter throws when uuid is null`() {
    val contributor = MockSearchEverywhereContributor("id")
    val info = SearchEverywhereFoundElementInfo("element", 100, contributor)
    assertThrows(IllegalArgumentException::class.java) {
      SearchResultAdapter.createAdapterFor(info)
    }
  }

  @Test
  fun `legacy adapter fetchRawItemIfExists returns element`() {
    val element = "my-element-object"
    val contributor = MockSearchEverywhereContributor("id")
    val info = SearchEverywhereFoundElementInfo("uuid-1", element, 100, contributor)
    val adapter = SearchResultAdapter.createAdapterFor(info)

    assertSame(element, adapter.fetchRawItemIfExists())
  }

  @Test
  fun `legacy adapter isSemantic is false for regular contributor`() {
    val contributor = MockSearchEverywhereContributor("id")
    val info = SearchEverywhereFoundElementInfo("uuid-1", "element", 100, contributor)
    val adapter = SearchResultAdapter.createAdapterFor(info)

    assertFalse(adapter.isSemantic)
  }

  @Test
  fun `Processed delegates provider to underlying adapter`() {
    val contributor = MockSearchEverywhereContributor("delegateId")
    val info = SearchEverywhereFoundElementInfo("uuid-1", "element", 100, contributor)
    val rawAdapter = SearchResultAdapter.createAdapterFor(info)
    val processed = SearchResultAdapter.Processed(rawAdapter, "element", null, null, null)

    assertEquals("delegateId", processed.provider.id)
  }

  @Test
  fun `Processed delegates originalWeight`() {
    val contributor = MockSearchEverywhereContributor("id")
    val info = SearchEverywhereFoundElementInfo("uuid-1", "element", 77, contributor)
    val rawAdapter = SearchResultAdapter.createAdapterFor(info)
    val processed = SearchResultAdapter.Processed(rawAdapter, "element", null, null, null)

    assertEquals(77, processed.originalWeight)
  }

  @Test
  fun `Processed delegates stateLocalId`() {
    val contributor = MockSearchEverywhereContributor("id")
    val info = SearchEverywhereFoundElementInfo("uuid-abc", "element", 100, contributor)
    val rawAdapter = SearchResultAdapter.createAdapterFor(info)
    val processed = SearchResultAdapter.Processed(rawAdapter, "element", null, null, null)

    assertEquals("uuid-abc", processed.stateLocalId.value)
  }

  @Test
  fun `Processed exposes its own fields`() {
    val contributor = MockSearchEverywhereContributor("id")
    val info = SearchEverywhereFoundElementInfo("uuid-1", "element", 100, contributor)
    val rawAdapter = SearchResultAdapter.createAdapterFor(info)
    val rawItem = "my-raw-item"
    val sessionWideId = SessionWideId(5)
    val mlFeatures = listOf<EventPair<*>>()
    val mlProbability = MlProbability(0.85)

    val processed = SearchResultAdapter.Processed(rawAdapter, rawItem, sessionWideId, mlFeatures, mlProbability)

    assertSame(rawItem, processed.rawItem)
    assertEquals(sessionWideId.value, processed.sessionWideId?.value)
    assertNotNull(processed.mlFeatures)
    assertTrue(processed.mlFeatures!!.isEmpty())
    assertEquals(mlProbability.value, processed.mlProbability?.value)
  }
}
