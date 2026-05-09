package com.daddylive

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.daddylive.DaddyLive
import com.daddylive.DaddyLiveExtractor

@CloudstreamPlugin
class DaddyLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DaddyLive())
        registerExtractorAPI(DaddyLiveExtractor(context))
    }
}