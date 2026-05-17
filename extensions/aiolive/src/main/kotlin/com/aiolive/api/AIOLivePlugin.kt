package com.aiolive.api

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AIOLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AIOLive())
        registerExtractorAPI(AIOLiveExtractor(context))
    }
}
