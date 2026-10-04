package com.deeptalking.core.common

/** Minimal result type shared by core/domain modules. */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: Throwable) : AppResult<Nothing>
    data object Loading : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
    AppResult.Loading -> AppResult.Loading
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value
