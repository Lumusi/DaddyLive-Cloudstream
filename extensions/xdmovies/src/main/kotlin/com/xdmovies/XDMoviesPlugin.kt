package com.xdmovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XDMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XDMoviesProvider())
    }
}
