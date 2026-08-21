package com.asia2tv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * نتيجة محاولة تسجيل الدخول: الكوكيز الناتجة (فاضية = فشل)
 */
data class Asia2TVLoginResult(
    val success: Boolean,
    val cookies: Map<String, String>,
    val message: String
)

/**
 * منطق تسجيل الدخول مشترك بين الـ Provider وشاشة الإعدادات،
 * عشان نقدر نختبر الدخول فورًا من الإعدادات بدون ما نفتح التطبيق كامل.
 */
object Asia2TVAuth {
    private const val PREFS = "asia2tv_prefs"
    private const val MAIN_URL = "https://asia2tv.com"

    private fun cookiesToString(cookies: Map<String, String>): String =
        cookies.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun cookiesFromString(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(";").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    fun loadSavedCookies(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return cookiesFromString(prefs.getString("asia2tv_cookies", null))
    }

    private fun saveCookies(context: Context, cookies: Map<String, String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("asia2tv_cookies", cookiesToString(cookies)).apply()
    }

    fun clearCookies(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove("asia2tv_cookies").apply()
    }

    /**
     * يسجل الدخول فعليًا ويحفظ الجلسة، ويرجع نتيجة واضحة (نجح/فشل + سبب)
     */
    suspend fun login(context: Context): Asia2TVLoginResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val username = prefs.getString("asia2tv_email", null)
        val password = prefs.getString("asia2tv_password", null)

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return Asia2TVLoginResult(false, emptyMap(), "لم يتم إدخال بيانات الدخول")
        }

        try {
            // 1) نجيب صفحة تسجيل الدخول أول مرة عشان نمسك الكوكيز الأولية (XSRF-TOKEN) و _token
            val loginPage = app.get("$MAIN_URL/login")
            val form = loginPage.document.selectFirst("form")
                ?: return Asia2TVLoginResult(false, emptyMap(), "ما قدرنا نلاقي فورم تسجيل الدخول")

            val initialCookies = loginPage.cookies

            val formData = HashMap<String, String>()
            form.select("input[type=hidden]").forEach { input ->
                val name = input.attr("name")
                if (name.isNotBlank()) formData[name] = input.attr("value")
            }

            // الحقول معروفة من فحص الموقع: email (فيها اسم المستخدم فعليًا) و password
            formData["email"] = username
            formData["password"] = password

            val hiddenFieldNames = formData.keys.joinToString(", ")
            val tokenValue = formData["_token"]

            // 2) موقع Laravel يحتاج X-XSRF-TOKEN بالهيدر، مأخوذ من كوكي XSRF-TOKEN (مفكوك الترميز)
            val xsrfCookie = initialCookies["XSRF-TOKEN"]
            val headers = HashMap<String, String>()
            if (xsrfCookie != null) {
                headers["X-XSRF-TOKEN"] = URLDecoder.decode(xsrfCookie, "UTF-8")
            }
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Accept"] = "application/json, text/plain, */*"

            val diagInfo = "حقول الفورم: $hiddenFieldNames | " +
                "طول _token: ${tokenValue?.length ?: 0} | " +
                "كوكيز أولية: ${initialCookies.keys.joinToString(", ")} | " +
                "XSRF موجود: ${xsrfCookie != null}"

            // 3) نرسل تسجيل الدخول
            val loginResponse = app.post(
                "$MAIN_URL/login",
                data = formData,
                cookies = initialCookies,
                headers = headers,
                referer = "$MAIN_URL/login",
                allowRedirects = true
            )

            val finalCookies = initialCookies + loginResponse.cookies
            if (finalCookies.isEmpty()) {
                return Asia2TVLoginResult(false, emptyMap(), "لم يتم استلام أي كوكيز من السيرفر\n\n$diagInfo")
            }

            // معلومات تشخيصية دقيقة: وين هبطنا فعليًا بعد إرسال تسجيل الدخول
            val postStatus = loginResponse.code
            val postDoc = try { loginResponse.document } catch (e: Exception) { null }
            val postTitle = postDoc?.title() ?: "غير معروف"
            val postHasPasswordField = postDoc?.select("input[type=password]")?.isNotEmpty() ?: false
            val postFinalUrl = try { loginResponse.url } catch (e: Exception) { "غير معروف" }

            // الدليل الأقوى: Laravel يغيّر معرف الجلسة (session) تلقائيًا بعد نجاح تسجيل الدخول
            // (session regenerate). لو القيمة تغيّرت، الدخول نجح فعليًا بغض النظر عن شكل الصفحة.
            val sessionBefore = initialCookies["asia2tvcom_session"]
            val sessionAfter = finalCookies["asia2tvcom_session"]
            val sessionChanged = sessionBefore != null && sessionAfter != null && sessionBefore != sessionAfter

            // 4) نتحقق أيضًا من محتوى الصفحة الرئيسية كدليل إضافي (مو أساسي)
            val checkDoc = app.get(MAIN_URL, cookies = finalCookies).document
            val pageText = checkDoc.text()
            val checkTitle = checkDoc.title()

            val hasLogoutHint = checkDoc.select("a[href*=logout], a[href*='تسجيل-خروج']").isNotEmpty() ||
                pageText.contains("تسجيل خروج") ||
                pageText.contains("تسجيل الخروج")

            val diagInfo2 = "رابط نهائي بعد الدخول: $postFinalUrl\n" +
                "عنوان صفحة رد الدخول: $postTitle\n" +
                "فيها حقل باسورد؟: $postHasPasswordField\n" +
                "تغيّر معرف الجلسة (الدليل الأهم): $sessionChanged\n" +
                "لقينا رابط خروج بالواجهة؟: $hasLogoutHint"

            return if (sessionChanged || hasLogoutHint) {
                saveCookies(context, finalCookies)
                Asia2TVLoginResult(true, finalCookies, "تم تسجيل الدخول بنجاح ✅\n\n$diagInfo2")
            } else {
                Asia2TVLoginResult(
                    false,
                    finalCookies,
                    "فشل الدخول\nكود الرد: $postStatus\n\n$diagInfo\n\n$diagInfo2"
                )
            }
        } catch (e: Exception) {
            return Asia2TVLoginResult(false, emptyMap(), "خطأ: ${e.message}")
        }
    }
}

class Asia2TV(private val context: Context? = null) : MainAPI() {
    override var mainUrl = "https://asia2tv.com"
    override var name = "Asia2TV"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    private var sessionCookies: Map<String, String> = emptyMap()
    private var loginAttempted = false

    private suspend fun ensureLoggedIn() {
        if (sessionCookies.isNotEmpty()) return

        // أول شي نجرب الكوكيز المحفوظة من قبل (بدون ما نعيد تسجيل الدخول كل مرة)
        context?.let {
            val saved = Asia2TVAuth.loadSavedCookies(it)
            if (saved.isNotEmpty()) {
                sessionCookies = saved
                return
            }
        }

        if (loginAttempted) return
        loginAttempted = true

        val ctx = context ?: return
        val result = Asia2TVAuth.login(ctx)
        if (result.success) {
            sessionCookies = result.cookies
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

        // كل حلقة موجودة داخل وسم <a id="pageepisodeN" href="..."> يحتوي
        // span.titlepisode فيه رقم/اسم الحلقة. هذا هو الـ selector الحقيقي
        // المكتشف من فحص صفحة المسلسل (serie/...) مباشرة.
        val episodeLinks = doc.select("a[id^=pageepisode]")

        if (episodeLinks.isNotEmpty()) {
            // القائمة بالموقع تجي من الأحدث للأقدم (816، 815، 814...)
            // نعكس الترتيب عشان تطلع بالتطبيق من الأقدم للأحدث (1، 2، 3...)
            val ordered = episodeLinks.reversed()

            ordered.forEachIndexed { index, element ->
                val epUrl = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

                val rawTitle = element.selectFirst(".titlepisode")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                // نحاول نستخرج رقم الحلقة الفعلي من النص (مثل "الحلقة 815"
                // أو "ح816: تغلب على الحر") عشان الترقيم يطابق الموقع تمامًا
                val extractedNumber = Regex("""\d+""").find(rawTitle)?.value?.toIntOrNull()

                episodes.add(
                    newEpisode(epUrl) {
                        name = rawTitle.ifEmpty { "الحلقة ${index + 1}" }
                        episode = extractedNumber ?: (index + 1)
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

        // اكتشفنا إن الفيديو يجي عن طريق iframe حقيقي بالـ HTML
        // (مثال: <iframe id="iframe_player" src="https://vidmoly.net/embed-....html">)
        // نتجاهل iframes الإعلانات (زي googleads/doubleclick) ونمرر الباقي فقط لـ loadExtractor
        var foundAny = false

        doc.select("iframe").forEach { element ->
            val src = fixUrlNull(element.attr("src")) ?: return@forEach

            if (src.contains("doubleclick") ||
                src.contains("googleads") ||
                src.contains("googlesyndication")
            ) {
                return@forEach
            }

            foundAny = true
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // احتياط: بعض الصفحات ممكن تحط الرابط بـ source/option بدل iframe
        if (!foundAny) {
            doc.select("source, option[value*='http']").forEach { element ->
                val src = fixUrlNull(
                    element.attr("src").ifEmpty { element.attr("value") }
                ) ?: return@forEach

                foundAny = true
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return foundAny
    }
}
