package com.asia2tv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.openWebView

@CloudstreamPlugin
class Asia2TVPlugin : Plugin() {
    override fun load(context: Context) {
        // تسجيل المزود الرئيسي
        registerMainAPI(Asia2TV())

        // إضافة زر الإعدادات المخصص المماثل لمستودع Phisher
        openSettings = { ctx ->
            ctx.openWebView("https://asia2tv.com/login", "تسجيل الدخول - Asia2TV")
        }
    }
}
