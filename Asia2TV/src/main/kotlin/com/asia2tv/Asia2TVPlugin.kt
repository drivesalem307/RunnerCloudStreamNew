package com.asia2tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Asia2TVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Asia2TV())

        openSettings = { ctx ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://asia2tv.com/login")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
    }
}
