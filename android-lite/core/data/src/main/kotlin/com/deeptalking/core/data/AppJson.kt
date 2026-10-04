package com.deeptalking.core.data

import kotlinx.serialization.json.Json

/** Shared JSON codec for every repository payload column. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
