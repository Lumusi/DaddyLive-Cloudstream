package com.damitv.api

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.damitv.api.DamiTV
import com.damitv.api.DamiTVExtractor

@CloudstreamPlugin
class DamiTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DamiTV())
        registerExtractorAPI(DamiTVExtractor(context))
    }
}
