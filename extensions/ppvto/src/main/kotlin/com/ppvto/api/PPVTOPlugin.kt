package com.ppvto.api

import com.lagradost.cloudstream3.Plugin
import com.lagradost.cloudstream3.registerMainAPI

class PPVTOPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PPVTO())
    }
}