package com.intellij.searchEverywhereMl.ranking.core.adapters

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo
import com.intellij.ide.actions.searcheverywhere.SemanticSearchEverywhereContributor
import com.intellij.internal.statistic.eventLog.events.EventPair

@JvmInline
value class SessionWideId(val value: Int)

@JvmInline
value class StateLocalId(private val value: String)

@JvmInline
value class MlProbability(val value: Double) {
  fun toWeight(): Int {
    return (value * 10_000).toInt()
  }
}

sealed interface SearchResultAdapter {
  fun getRawItem(): Any

  val provider: SearchResultProviderAdapter

  val originalWeight: Int

  val isSemantic: Boolean

  /**
   * Unique identifier of the element in the current search state
   *
   * The same element that appears in different search states may have different identifiers.
   * We use this identifier to cache ML-specific information about the element.
   */
  val stateLocalId: StateLocalId

  companion object {
    fun createAdapterFor(foundElementInfo: SearchEverywhereFoundElementInfo): Raw {
      return LegacyFoundElementInfoAdapter(foundElementInfo)
    }
  }

  interface Raw : SearchResultAdapter

  data class Processed(
    private val adapter: SearchResultAdapter,
    val sessionWideId: SessionWideId?,
    val mlFeatures: List<EventPair<*>>?,
    val mlProbability: MlProbability?,
  ) : SearchResultAdapter by adapter
}

private class LegacyFoundElementInfoAdapter(private val foundElementInfo: SearchEverywhereFoundElementInfo) : SearchResultAdapter.Raw {
  override fun getRawItem(): Any = foundElementInfo.element

  override val provider: SearchResultProviderAdapter = SearchResultProviderAdapter.createAdapterFor(foundElementInfo.contributor)

  override val originalWeight: Int
    get() = foundElementInfo.priority

  override val isSemantic: Boolean
    get() = (foundElementInfo.contributor as? SemanticSearchEverywhereContributor)?.isElementSemantic(foundElementInfo.element) ?: false

  override val stateLocalId: StateLocalId =
    StateLocalId(requireNotNull(foundElementInfo.uuid) { "UUID cannot be null for ${foundElementInfo::class.java.simpleName}" })
}
