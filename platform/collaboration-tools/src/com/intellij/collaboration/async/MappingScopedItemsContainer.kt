// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.collaboration.async

import com.intellij.collaboration.util.HashingUtil
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.containers.CollectionFactory
import com.intellij.util.containers.HashingStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus

/**
 * Associate each *item* [T] *key* [K] in the iterable from the receiver flow (source list) with a *value* [V]
 *
 * Keys are distinguished by a [hashingStrategy]
 *
 * When a new iterable is received:
 * * a new [CoroutineScope] and a new value is created via [valueExtractor] for new items
 * * existing values are updated via [update] if it was supplied
 * * values for missing items are removed and their scope is cancelled
 *
 * Order of the values in the resulting list is the same as in the source iterable
 * All [CoroutineScope]'s of values are only active while the resulting flow is being collected
 *
 * **Returned flow never completes**
 */
fun <T, K, V> Flow<Iterable<T>>.associateCachingBy(
  keyExtractor: (T) -> K,
  hashingStrategy: HashingStrategy<K>,
  valueExtractor: CoroutineScope.(T) -> V,
  update: (suspend V.(T) -> Unit)? = null,
): Flow<List<V>> = flow {
  coroutineScope {
    val container = MappingScopedItemsContainer(this, keyExtractor, hashingStrategy, valueExtractor, update)
    collect {
      container.update(it)
      emit(container.mappedState.value)
    }
    awaitCancellation()
  }
}

/**
 * @see associateCachingBy
 */
fun <T, R> Flow<Iterable<T>>.associateCachingWith(
  hashingStrategy: HashingStrategy<T>,
  mapper: CoroutineScope.(T) -> R,
  update: (suspend R.(T) -> Unit)? = null,
): Flow<List<R>> {
  return associateCachingBy({ it }, hashingStrategy, { mapper(it) }, update)
}

/**
 * Creates a list of stateful objects from the list of DTO (Data Transfer Object)
 * Stateful objects are updated with [update] when a DTO identified by a [sourceIdentifier] changes
 */
fun <T, R> Flow<Iterable<T>>.mapDataToModel(
  sourceIdentifier: (T) -> Any,
  mapper: CoroutineScope.(T) -> R,
  update: (suspend R.(T) -> Unit),
): Flow<List<R>> =
  associateCachingWith(HashingUtil.mappingStrategy(sourceIdentifier), mapper, update)

/**
 * Creates a list of stateful objects from other stateful objects, comparing the original objects by identity
 */
fun <T, R> Flow<Iterable<T>>.mapStatefulToStateful(mapper: CoroutineScope.(T) -> R): Flow<List<R>> =
  associateCachingWith(HashingStrategy.identity(), mapper)

/**
 * Allows mapping a collection of items [T] to scoped (coroutine scope bound) values [V]
 * An intermittent key [K] is used to uniquely identify items
 *
 * @param cs parent scope for value scopes
 * @param keyExtractor should be a quick-to-run function extracting a key from item
 * @param hashingStrategy strategy used to compare keys
 * @param mapper factory function to create a value from item
 * @param update function used to update value if a new item is supplied for the existing key
 */
@ApiStatus.Internal
class MappingScopedItemsContainer<T, K, V> internal constructor(
  private val cs: CoroutineScope,
  private val keyExtractor: (T) -> K,
  private val hashingStrategy: HashingStrategy<K>,
  private val mapper: CoroutineScope.(T) -> V,
  private val update: (suspend V.(T) -> Unit)? = null,
) {
  private val _mappingState = MutableStateFlow(MappingState(hashingStrategy))
  val mappedState: StateFlow<List<V>> = _mappingState.mapState { it.values.map { v -> v.value } }
  private val mapGuard = Mutex()

  suspend fun update(items: Iterable<T>): Unit = mapGuard.withLock {
    withContext(NonCancellable) {
      val currentMap = _mappingState.value
      val resultMap = MappingState(hashingStrategy)

      // add everything to the new mapping
      for (item in items) {
        val itemKey = keyExtractor(item)
        val existing = currentMap[itemKey]

        if (existing == null) {
          val valueScope = cs.childScope("item=$itemKey")
          resultMap[itemKey] = ScopingWrapper(valueScope, mapper(valueScope, item))
        }
        else {
          // if not inferring nullability fsr
          update?.let { existing.value.it(item) }
          resultMap[itemKey] = existing
        }
      }

      // cancel scopes that are no longer included in the list
      val deletedKeys = currentMap.keys.toSet() - resultMap.keys.toSet()
      for (key in deletedKeys) {
        val scopedValue = currentMap[key] ?: continue
        scopedValue.cancel()
      }

      _mappingState.value = resultMap
    }
  }

  suspend fun addIfAbsent(item: T): V = mapGuard.withLock {
    withContext(NonCancellable) {
      val key = keyExtractor(item)
      _mappingState.value[key]?.value ?: _mappingState.updateAndGet {
        val newWrapper = it.copy()
        val valueScope = cs.childScope("item=$key")
        val newValue = ScopingWrapper(valueScope, mapper(valueScope, item))
        newWrapper[key] = newValue
        newWrapper
      }[key]!!.value
    }
  }

  companion object {
    fun <T, V> byIdentity(cs: CoroutineScope, mapper: CoroutineScope.(T) -> V): MappingScopedItemsContainer<T, T?, V> =
      MappingScopedItemsContainer(cs, { it }, HashingStrategy.identity(), mapper, {})

    fun <T, V> byEquality(cs: CoroutineScope, mapper: CoroutineScope.(T) -> V): MappingScopedItemsContainer<T, T?, V> =
      MappingScopedItemsContainer(cs, { it }, HashingStrategy.canonical(), mapper, {})
  }

  private inner class MappingState(private val hashingStrategy: HashingStrategy<K>) {
    private val _map: MutableMap<K, ScopingWrapper<V>> = CollectionFactory.createLinkedCustomHashingStrategyMap(hashingStrategy)
    val keys: List<K> get() = _map.keys.toList()
    val values: List<ScopingWrapper<V>> get() = _map.values.toList()

    operator fun get(key: K): ScopingWrapper<V>? = _map[key]

    operator fun set(key: K, value: ScopingWrapper<V>) {
      _map[key] = value
    }

    fun copy(): MappingState =
      MappingState(hashingStrategy).also { copy ->
        copy._map.putAll(_map)
      }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is MappingScopedItemsContainer<*, *, *>.MappingState) return false
      if (keys != other.keys) return false
      return keys.all { _map[it] == other._map[it] }
    }

    override fun hashCode(): Int {
      var result = keys.hashCode()
      for (key in keys) {
        result = 31 * result + (_map[key].hashCode())
      }
      return result
    }
  }
}

private data class ScopingWrapper<T>(val scope: CoroutineScope, val value: T) {
  suspend fun cancel() = scope.cancelAndJoinSilently()
}