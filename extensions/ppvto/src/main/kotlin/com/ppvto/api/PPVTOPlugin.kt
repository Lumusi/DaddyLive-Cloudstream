package com.ppvto

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PpvToPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PpvTo())
    }
}