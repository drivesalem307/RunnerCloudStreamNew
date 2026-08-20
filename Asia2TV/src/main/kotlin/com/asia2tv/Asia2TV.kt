package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class Asia2TV : MainAPI() {

    override var mainUrl = "https://asia2tv.com"
    override var name = "Asia2TV"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    companion object {
        var username = "jumanalla"
        var password = "exo12345"
        var loggedIn = false
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val a = select("a").firstOrNull() ?: return null

        val title = select("h2,h3,.title,.post-title")
            .text()
            .trim()
            .ifEmpty { a.text().trim() }

        if (title.isEmpty()) return null

        val url = fixUrlNull(a.attr("href")) ?: return null
        val poster = fixUrlNull(select("img").attr("src"))

        return newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(mainUrl).document

        val items = doc.select(
            "article, .post-home.cont, .post-item, .post-info"
        ).mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Asia2TV",
                    items
                )
            )
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select(
            "article, .post-home.cont, .post-item, .post-info"
        ).mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {

        login()

        val doc = app.get(url).document

        val title = doc.select(
            "h1, h1.entry-title, .post-title"
        ).text().trim()

        val poster = fixUrlNull(
            doc.select("img").firstOrNull()?.attr("src")
        )

        val description = doc.select(
            ".entry-content, .post-body, .story"
        ).text().trim()

        val episodes = ArrayList<Episode>()

        doc.select(
            "a[href*='episode'], " +
            "a[href*='/p/'], " +
            ".episodes-list a, " +
            ".ep-list a"
        ).forEachIndexed { index, element ->

            val epUrl = fixUrlNull(element.attr("href"))
                ?: return@forEachIndexed

            episodes.add(
                newEpisode(epUrl) {
                    name = element.text().trim()
                        .ifEmpty { "الحلقة ${index + 1}" }
                    episode = index + 1
                }
            )
        }

        if (episodes.isEmpty()) {
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

        login()

        val doc = app.get(data).document
        var found = false

        doc.select("iframe").forEach { iframe ->

            val src = fixUrlNull(
                iframe.attr("src")
            ) ?: return@forEach

            found = true

            loadExtractor(
                src,
                data,
                subtitleCallback,
                callback
            )
        }

        doc.select(
            "video source, video"
        ).forEach { video ->

            val src = fixUrlNull(
                video.attr("src").ifEmpty {
                    video.attr("data-src")
                }
            ) ?: return@forEach

            found = true

            callback(
                newExtractorLink(
                    name,
                    name,
                    src,
                    ExtractorLinkType.VIDEO
                ) {
                    referer = data
                }
            )
        }

        doc.select(
            "a[href*='watch'], " +
            "a[href*='embed'], " +
            "a[href*='stream'], " +
            "a[href*='player']"
        ).forEach { link ->

            val href = fixUrlNull(
                link.attr("href")
            ) ?: return@forEach

            found = true

            loadExtractor(
                href,
                data,
                subtitleCallback,
                callback
            )
        }

        return found
    }

    private suspend fun login(): Boolean {

        if (loggedIn) return true

        return try {

            val loginPage = app.get(
                "$mainUrl/login"
            )

            val token = loginPage.document
                .select("input[name=_token]")
                .attr("value")

            if (token.isEmpty()) {
                loggedIn = false
                return false
            }

            val response = app.submitForm(
                url = "$mainUrl/login",
                form = mapOf(
                    "_token" to token,
                    "email" to username,
                    "password" to password
                ),
                headers = mapOf(
                    "Referer" to "$mainUrl/login"
                )
            )

            loggedIn = response.isSuccessful

            loggedIn

        } catch (e: Exception) {

            loggedIn = false
            false
        }
    }
}

@CloudstreamPlugin
class Asia2TVPlugin : Plugin() {

    override fun load(context: android.content.Context) {
        registerMainAPI(Asia2TV())
    }
}
