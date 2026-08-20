package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class Asia2TVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Asia2TV())
    }
}

class Asia2TV : MainAPI() {

    override var mainUrl = "https://asia2tv.com"
    override var name = "Asia2TV"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl).document

        val items = doc.select(
            "article, div.post-item, .post-main"
        ).mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList("أحدث المسلسلات", items)
        )
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val link = select("a").firstOrNull() ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val title = select(".title, h2, h3")
            .text()
            .trim()
            .ifEmpty { link.text().trim() }

        if (title.isBlank()) return null

        val poster = fixUrlNull(select("img").attr("src"))

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.AsianDrama
        ) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document

        return doc.select(
            "article, div.post-item, .post-main"
        ).mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.select(
            "h1.entry-title, .post-title, h1"
        ).firstOrNull()?.text()?.trim() ?: "Asia2TV"

        val poster = fixUrlNull(
            doc.select(".poster img, .entry-content img").attr("src")
        )

        val description = doc.select(
            ".entry-content, .story"
        ).text().trim()

        val episodes = ArrayList<Episode>()

        doc.select(
            "a[href*='/episode/'], a[href*='episode'], .episodes-list a"
        ).forEachIndexed { index, element ->

            val epUrl = fixUrlNull(element.attr("href"))
                ?: return@forEachIndexed

            val epName = element.text()
                .trim()
                .ifEmpty { "الحلقة ${index + 1}" }

            episodes.add(
                newEpisode(epUrl) {
                    name = epName
                    episode = index + 1
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.AsianDrama,
            episodes
        ) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        var found = false

        doc.select(
            "iframe, a[href*='/embed/'], a[href*='/watch/']"
        ).forEach { element ->

            val rawUrl = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("href") }

            val url = fixUrlNull(rawUrl)
                ?: return@forEach

            found = true

            loadExtractor(
                url,
                data,
                subtitleCallback,
                callback
            )
        }

        return found
    }
}
