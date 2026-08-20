package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Asia2TV : MainAPI() {
    override var mainUrl = "https://asia2tv.com"
    override var name = "Asia2TV"
    override var lang = "ar"
    override val hasMainPage = true

    // تفعيل دعم خيار تسجيل الدخول بالحساب داخل إعدادات الإضافة
    override val hasOAuth = true 

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    // دالة تسجيل الدخول (تحفظ الجلسة والكوكيز محلياً في التطبيق)
    override suspend fun login(credentials: AuthCredentials): Boolean {
        return try {
            val loginUrl = "$mainUrl/login"
            val response = app.post(
                loginUrl,
                data = mapOf(
                    "log" to credentials.account,
                    "pwd" to credentials.password,
                    "wp-submit" to "Log In"
                )
            )
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val homeCategories = ArrayList<HomePageList>()

        val items = doc.select("article, div.post-item, .post-main").mapNotNull {
            it.toSearchResult()
        }

        if (items.isNotEmpty()) {
            homeCategories.add(HomePageList("أحدث المسلسلات والحلقات", items))
        }

        return newHomePageResponse(homeCategories)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.select("a").first() ?: return null
        val title = this.select(".title, h2, h3").text().trim().ifEmpty { titleElement.text().trim() }
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.select("img").attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("article, div.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("h1.entry-title, .post-title").text().trim()
        val poster = fixUrlNull(doc.select(".poster img, .entry-content img").attr("src"))
        val description = doc.select(".entry-content p, .story").text().trim()

        val episodes = ArrayList<Episode>()
        val episodeElements = doc.select("a[href*=/episode/], a[href*=-episode-], .episodes-list a")

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

        doc.select("iframe, a[href*=/embed/], a[href*=/watch/]").forEach { element ->
            val src = fixUrlNull(element.attr("src").ifEmpty { element.attr("href") }) ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}
