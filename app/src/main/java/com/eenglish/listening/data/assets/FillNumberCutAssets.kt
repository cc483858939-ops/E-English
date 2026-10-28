package com.eenglish.listening.data.assets

import android.content.res.AssetManager
import com.eenglish.listening.domain.layout.FillNumberCutCatalog
import kotlinx.serialization.json.Json

/** One immutable catalog shared by practice, transcript and review renderers. */
object FillNumberCutAssets {
    @Volatile private var cached: FillNumberCutCatalog? = null

    fun load(assets: AssetManager): FillNumberCutCatalog = cached ?: synchronized(this) {
        cached ?: runCatching {
            assets.open("fill-number-cuts.json").bufferedReader().use {
                Json.decodeFromString<FillNumberCutCatalog>(it.readText())
            }
        }.getOrElse { FillNumberCutCatalog(1, emptyList()) }.also { cached = it }
    }
}
