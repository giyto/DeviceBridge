package ru.hznik.devicebridge.feature.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** One reading of a screen's stored data: under way, its latest value, or failed. */
internal sealed interface LoadResult<out T> {
    data object Loading : LoadResult<Nothing>
    data class Loaded<T>(val value: T) : LoadResult<T>
    data object Failed : LoadResult<Nothing>
}

/** Emits [LoadResult.Loading] first, then each value, and ends with [LoadResult.Failed] on an error. */
internal fun <T> Flow<T>.asLoadResult(): Flow<LoadResult<T>> =
    map<T, LoadResult<T>> { LoadResult.Loaded(it) }
        .onStart { emit(LoadResult.Loading) }
        .catch { emit(LoadResult.Failed) }
