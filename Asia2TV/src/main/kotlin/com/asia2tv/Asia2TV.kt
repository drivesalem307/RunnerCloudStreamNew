package com.asia2tv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Asia2TV(private val context: Context? = null) : MainAPI() {
    override var mainUrl = "https://asia2tv.com"
    override var name = "Asia2TV"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    private val prefs by lazy {
        context?.getSharedPreferences("asia2tv_prefs", Context.MODE_PRIVATE)
    }

    private var sessionCookies: Map<String, String> = emptyMap()
    private var loginAttempted = false

    /**
     * يسجل الدخول تلقائيًا باستخدام البيانات المحفوظة بالإعدادات.
     * يكتشف أسماء حقول الفورم (email/password/csrf) تلقائيًا بدل ما يفترضها ثابتة،
     * عشان يشتغل حتى لو الموقع غيّر أسماء الحقول.
     */
    private suspend fun ensureLoggedIn() {
        if (sessionCookies.isNotEmpty() || loginAttempted) return
        loginAttempted = true

        val email = prefs?.getString("asia2tv_email", null)
        val password = prefs?.getString("asia2tv_password", null)
        if (email.isNullOrBlank() || password.isNullOrBlank()) return

        try {
            val loginPageResponse = app.get("$mainUrl/login")
            val form = loginPageResponse.document.selectFirst("form") ?: return

            val actionAttr = form.attr("action")
            val actionUrl = when {
                actionAttr.isBlank() -> "$mainUrl/login"
                actionAttr.startsWith("http") -> actionAttr
                else -> fixUrlNull(actionAttr) ?: "$mainUrl/login"
            }

            val formData = HashMap<String, String>()

            // نمسك أي حقول مخفية (زي CSRF token) ونحافظ على قيمتها الأصلية
            form.select("input[type=hidden]").forEach { input ->
                val name = input.attr("name")
                if (name.isNotBlank()) formData[name] = input.attr("value")
            }

            // نكتشف اسم حقل الإيميل/اليوزر تلقائيًا
            val emailFieldName = form.select("input[type=email], input[type=text]")
                .firstOrNull()?.attr("name")
            // نكتشف اسم حقل الباسوورد تلقائيًا
            val passwordFieldName = form.select("input[type=password]")
                .firstOrNull()?.attr("name")

            if (emailFieldName.isNullOrBlank() || passwordFieldName.isNullOrBlank()) {
                return // ما قدرنا نلاقي الحقول، لا نكمل تسجيل الدخول
            }

            formData[emailFieldName] = email
            formData[passwordFieldName] = password

            val loginResponse = app.post(
                actionUrl,
                data = formData,
                cookies = loginPageResponse.cookies,
                referer = "$mainUrl/login",
                allowRedirects = true
            )

            val cookies = loginPageResponse.cookies + loginResponse.cookies
            if (cookies.isNotEmpty()) {
                sessionCookies = cookies
            }
        } catch (e: Exception) {
            // نتجاهل الخطأ ونكمل بدون تسجيل دخول بدل ما نوقف التطبيق
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        ensureLoggedIn()
        val doc = app.get(mainUrl, cookies = sessionCookies).document
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
        ensureLoggedIn()
        val doc = app.get("$mainUrl/?s=$query", cookies = sessionCookies).document

        return doc.select("article, div.post-item, .post-main")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {
        ensureLoggedIn()
        val doc = app.get(url, cookies = sessionCookies).document

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
        ensureLoggedIn()
        val doc = app.get(data, cookies = sessionCookies).document

        doc.select("iframe, source, option[value*='http']").forEach { element ->
            val src = fixUrlNull(
                element.attr("src").ifEmpty { element.attr("value") }
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
