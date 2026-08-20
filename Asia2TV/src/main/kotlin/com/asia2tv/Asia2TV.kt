package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        val items = doc.select("article, div.post-item, .post-main")
            .mapNotNull { it.toSearchResult() }

        return if (items.isNotEmpty()) {
            newHomePageResponse(
                HomePageList("Asia2TV", items)
            )
        } else {
            newHomePageResponse(emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = select("a").firstOrNull() ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val title = select(".title, h2, h3")
            .text()
            .trim()
            .ifEmpty { link.text().trim() }

        if (title.isBlank()) return null

        val poster = fixUrlNull(
            select("img").attr("src")
        )

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.AsianDrama
        ) {
            posterUrl = poster
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("article, div.post-item, .post-main")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {
        val doc = app.get(url).document

        val title = doc.select(
            "h1.entry-title, .post-title, h1"
        ).firstOrNull()?.text()?.trim()
            ?: "Asia2TV"

        val poster = fixUrlNull(
            doc.select(
                ".poster img, .entry-content img, article img"
            ).firstOrNull()?.attr("src")
        )

        val description = doc.select(
            ".entry-content, .story, .post-content"
        ).text().trim()

        val episodes = ArrayList<Episode>()

        val episodeLinks = doc.select(
            "a[href*='/episode/'], " +
            "a[href*='episode'], " +
            ".episodes-list a"
        )

        if (episodeLinks.isNotEmpty()) {
            episodeLinks.forEachIndexed { index, element ->
                val epUrl = fixUrlNull(
                    element.attr("href")
                ) ?: return@forEachIndexed

                episodes.add(
                    newEpisode(epUrl) {
                        name = element.text()
                            .trim()
                            .ifEmpty { "الحلقة ${index + 1}" }

                        episode = index + 1
                    }
                )
            }
        } else {
            episodes.add(
                newEpisode(url) {
                    name = title
                    episode = 1
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
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("iframe").forEach { iframe ->
            val src = fixUrlNull(
                iframe.attr("src")
            ) ?: return@forEach

            loadExtractor(
                src,
                data,
                subtitleCallback,
                callback
            )
        }

        return true
    }
}
