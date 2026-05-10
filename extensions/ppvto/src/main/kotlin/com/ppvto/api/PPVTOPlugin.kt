package com.ppvto.api

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.ppvto.api.PPVTO

@CloudstreamPlugin
class PPVTOPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PPVTO())
    }
}