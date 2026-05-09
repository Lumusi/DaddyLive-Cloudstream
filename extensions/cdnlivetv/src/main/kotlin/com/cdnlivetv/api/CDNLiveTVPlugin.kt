package com.cdnlivetv.api

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cdnlivetv.api.CDNLiveTV
import com.cdnlivetv.api.CDNLiveTVExtractor

@CloudstreamPlugin
class CDNLiveTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CDNLiveTV())
        registerExtractorAPI(CDNLiveTVExtractor(context))
    }
}