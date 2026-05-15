package com.damitv.api

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DamiTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DamiTV())
    }
}
