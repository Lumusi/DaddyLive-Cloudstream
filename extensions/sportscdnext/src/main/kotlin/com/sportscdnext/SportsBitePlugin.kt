package com.sportscdnext

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.sportscdnext.SportsBite
import com.sportscdnext.SportsBiteExtractor

@CloudstreamPlugin
class SportsBitePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SportsBite())
        registerExtractorAPI(SportsBiteExtractor(context))
    }
}