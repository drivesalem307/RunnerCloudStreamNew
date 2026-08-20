package com.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

class ArabRunners : MainAPI() {
    override var mainUrl = "https://www.arabrunnersteam.org"
    override var name = "Arab Runners"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val homeCategories = ArrayList<HomePageList>()

        val items = doc.select("div.post-home.cont, div.post-info, article").mapNotNull {
            it.toSearchResult()
        }

        if (items.isNotEmpty()) {
            homeCategories.add(HomePageList("أحدث المشاركات", items))
        }

        return newHomePageResponse(homeCategories)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.select("a.Title, .post-title .title, h2 a").first() ?: return null
        val title = titleElement.text().trim()
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.select("img").attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url).document
        return doc.select("div.post-home.cont, div.post-info, article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("h1, h2.post-title").text().trim()
        val poster = fixUrlNull(doc.select("div.post-body img, article img").attr("src"))
        val description = doc.select("div.post-body").text().trim()

        val episodes = ArrayList<Episode>()
        val episodeElements = doc.select("div.post-body a[href*=/p/], div.post-body a[href*=.html]")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, el ->
                val epUrl = fixUrlNull(el.attr("href")) ?: return@forEachIndexed
                val epName = el.text().trim().ifEmpty { "الحلقة ${index + 1}" }
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        } else {
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // جمع كافة العناصر التي تحتمل وجود روابط (روابط عادية وإطارات iframe)
        val elements = doc.select("a[href], iframe[src]")

        for (element in elements) {
            val rawUrl = fixUrlNull(element.attr("href").ifEmpty { element.attr("src") }) ?: continue
            var targetUrl = rawUrl

            // إذا كان الرابط يخص Linkvertise ونطاقاته المشابهة
            if (isLinkvertiseUrl(targetUrl)) {
                val bypassedUrl = bypassLinkvertise(targetUrl)
                if (bypassedUrl != null) {
                    targetUrl = bypassedUrl
                }
            }

            // تمرير الرابط للمستخرج للتجربة
            loadExtractor(targetUrl, data, subtitleCallback, callback)
        }

        // استخراج ملفات الترجمة
        doc.select("a[href*=.srt], a[href*=.vtt], a[href*=.ass], a[href*=/file/]").forEach { sub ->
            val subUrl = fixUrlNull(sub.attr("href")) ?: return@forEach
            val subName = sub.text().trim()
            if (subName.contains("ترجمة") || subName.contains("عرب") || subUrl.contains(".srt") || subUrl.contains(".vtt")) {
                subtitleCallback(SubtitleFile("Arabic", subUrl))
            }
        }

        return true
    }

    private fun isLinkvertiseUrl(url: String): Boolean {
        return url.contains("linkvertise.com") || 
               url.contains("link-to.net") || 
               url.contains("up-to-down.net") || 
               url.contains("direct-link.net") ||
               url.contains("linkvertise.net")
    }

    // دالة تجاوز الاختصار المحدثة باستخدام عدة خدمات فك آمنة
    private suspend fun bypassLinkvertise(url: String): String? {
        // المحاولة الأولى: استخدام API bypass.vip
        try {
            val apiUrl = "https://api.bypass.vip/bypass?url=${url}"
            val responseText = app.get(apiUrl).text
            val json = JSONObject(responseText)
            if (json.optBoolean("success", false)) {
                val destination = json.optString("destination", "")
                if (destination.isNotEmpty() && destination.startsWith("http")) {
                    return destination
                }
            }
        } catch (_: Exception) {}

        // المحاولة الثانية: استخدام API bypass.city
        try {
            val apiUrl = "https://bypass.city/api/bypass?url=${url}"
            val responseText = app.get(apiUrl).text
            val json = JSONObject(responseText)
            val destination = json.optString("destination", "")
            if (destination.isNotEmpty() && destination.startsWith("http")) {
                return destination
            }
        } catch (_: Exception) {}

        return null
    }
}
