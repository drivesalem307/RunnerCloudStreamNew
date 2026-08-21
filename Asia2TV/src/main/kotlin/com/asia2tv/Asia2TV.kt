package com.asia2tv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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

            // موقع Laravel يحتاج X-XSRF-TOKEN بالهيدر (خاص بتسجيل الدخول تحديدًا)،
            // مأخوذ من كوكي XSRF-TOKEN (مفكوك الترميز)
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

            // 2) نرسل تسجيل الدخول
            val loginResponse = app.post(
                "$MAIN_URL/login",
                data = formData,
                cookies = initialCookies,
                headers = headers,
                referer = "$MAIN_URL/login",
                allowRedirects = false
            )

            val finalCookies = initialCookies + loginResponse.cookies
            if (finalCookies.isEmpty()) {
                return Asia2TVLoginResult(false, emptyMap(), "لم يتم استلام أي كوكيز من السيرفر\n\n$diagInfo")
            }

            val postStatus = loginResponse.code
            val redirectLocation = loginResponse.headers["location"] ?: "لا يوجد"

            val sessionBefore = initialCookies["asia2tvcom_session"]
            val sessionAfter = finalCookies["asia2tvcom_session"]
            val sessionChanged = sessionBefore != null && sessionAfter != null && sessionBefore != sessionAfter

            // 3) التحقق الحاسم: نفتح صفحة مسلسل معروف بهالكوكيز، ونشوف هل نشوف محتوى
            // العضو الحقيقي (روابط الحلقات) أو محتوى الزائر (بانر الصفحة الرئيسية)
            val verifyDoc = app.get(
                "$MAIN_URL/serie/2016-running-man",
                cookies = finalCookies
            ).document
            val episodeLinksFound = verifyDoc.select("a[id^=pageepisode]").size
            val actuallyLoggedIn = episodeLinksFound > 0

            val diagInfo2 = "كود رد تسجيل الدخول: $postStatus\n" +
                "رابط التحويل (Location): $redirectLocation\n" +
                "تغيّر معرف الجلسة: $sessionChanged\n" +
                "عدد روابط الحلقات بصفحة Running Man (التحقق الحاسم): $episodeLinksFound"

            return if (actuallyLoggedIn) {
                saveCookies(context, finalCookies)
                Asia2TVLoginResult(true, finalCookies, "تم تسجيل الدخول بنجاح فعليًا ✅\n\n$diagInfo2")
            } else {
                Asia2TVLoginResult(
                    false,
                    finalCookies,
                    "الدخول لم ينجح فعليًا رغم تغيّر الجلسة ❌\n\n$diagInfo\n\n$diagInfo2"
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

    // هذا الـ provider مخصص لمسلسل واحد بس (Running Man) بناءً على طلب المستخدم
    private val fixedShowUrl = "https://asia2tv.com/serie/2016-running-man"
    private val fixedShowTitle = "Running Man"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val item = newTvSeriesSearchResponse(
            fixedShowTitle,
            fixedShowUrl,
            TvType.AsianDrama
        )

        return newHomePageResponse(
            HomePageList("Asia2TV", listOf(item))
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        val item = newTvSeriesSearchResponse(
            fixedShowTitle,
            fixedShowUrl,
            TvType.AsianDrama
        )
        return listOf(item)
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

        val episodeLinks = doc.select("a[id^=pageepisode]")

        if (episodeLinks.isNotEmpty()) {
            val ordered = episodeLinks.reversed()

            ordered.forEachIndexed { index, element ->
                val epUrl = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

                val rawTitle = element.selectFirst(".titlepisode")
                    ?.text()
                    ?.trim()
                    .orEmpty()

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

        val diagLines = ArrayList<String>()
        diagLines.add("كوكيز الجلسة الحالية: ${if (sessionCookies.isEmpty()) "فاضية (غير مسجل دخول!)" else sessionCookies.keys.joinToString(", ")}")

        val episodeResponse = app.get(data, cookies = sessionCookies)
        val doc = episodeResponse.document

        diagLines.add("كود صفحة الحلقة: ${episodeResponse.code}")
        diagLines.add("رابط الحلقة النهائي: ${episodeResponse.url}")

        // الاكتشاف المهم: طلب ajaxGetRequest يحتاج هيدر X-CSRF-TOKEN
        // (مختلف عن X-XSRF-TOKEN المستخدم بتسجيل الدخول) — قيمته تُقرأ
        // من <meta name="csrf-token"> الموجودة بنفس صفحة الحلقة نفسها
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
        diagLines.add("CSRF token موجود؟: ${!csrfToken.isNullOrBlank()} (طول: ${csrfToken?.length ?: 0})")

        // نلقط بس أزرار "السيرفرات المجانية" (نتجاهل VIP كليًا)
        val freeServerLinks = doc.select("div:has(button:contains(المجانية)) a[data-code]")
        diagLines.add("عدد السيرفرات المجانية اللي لقيناها بالصفحة: ${freeServerLinks.size}")

        if (freeServerLinks.isEmpty()) {
            showDiagnosticDialog("لا يوجد سيرفرات", diagLines)
            return false
        }

        var foundAny = false

        val ajaxHeaders = HashMap<String, String>()
        ajaxHeaders["X-Requested-With"] = "XMLHttpRequest"
        ajaxHeaders["Accept"] = "application/json, text/plain, */*"
        if (!csrfToken.isNullOrBlank()) {
            ajaxHeaders["X-CSRF-TOKEN"] = csrfToken
        }

        // نجرب كل السيرفرات المجانية (مو بس أول وحدة تنجح)، عشان يطلع
        // للمستخدم أكثر من خيار "Source" بقائمة Cloudstream
        for (serverLink in freeServerLinks) {
            val serverName = serverLink.selectFirst("span")?.text()?.trim() ?: "؟"
            val code = serverLink.attr("data-code")
            if (code.isBlank()) {
                diagLines.add("[$serverName] data-code فاضي، تجاهلناه")
                continue
            }

            try {
                val ajaxResponse = app.post(
                    "$mainUrl/ajaxGetRequest",
                    data = mapOf(
                        "action" to "iframe_server",
                        "code" to code
                    ),
                    cookies = sessionCookies,
                    referer = data,
                    headers = ajaxHeaders
                )

                val bodySnippet = ajaxResponse.text.take(150)
                diagLines.add("[$serverName] كود الرد: ${ajaxResponse.code} | المقتطف: $bodySnippet")

                val json = JSONObject(ajaxResponse.text)
                if (!json.optBoolean("status", false)) {
                    diagLines.add("[$serverName] status=false بالرد، تجاهلناه")
                    continue
                }

                val codeplayHtml = json.optString("codeplay")
                if (codeplayHtml.isBlank()) {
                    diagLines.add("[$serverName] codeplay فاضي")
                    continue
                }

                val src = fixUrlNull(
                    org.jsoup.Jsoup.parse(codeplayHtml).selectFirst("iframe")?.attr("src")
                )
                if (src == null) {
                    diagLines.add("[$serverName] ما لقينا iframe جوا الرد")
                    continue
                }

                if (src.contains("doubleclick") ||
                    src.contains("googleads") ||
                    src.contains("googlesyndication")
                ) {
                    diagLines.add("[$serverName] إعلان، تجاهلناه")
                    continue
                }

                diagLines.add("[$serverName] ✅ رابط سليم: $src")
                foundAny = true
                loadExtractor(src, data, subtitleCallback, callback)
                // ملاحظة: ما نوقف هنا (لا break) — نكمل باقي السيرفرات المجانية
            } catch (e: Exception) {
                diagLines.add("[$serverName] استثناء: ${e.message}")
                continue
            }
        }

        if (!foundAny) {
            showDiagnosticDialog("فشل تحميل كل السيرفرات", diagLines)
        }

        return foundAny
    }

    /**
     * يعرض نافذة تشخيصية فورية جوا التطبيق تفصّل وش صار مع كل سيرفر —
     * بدل ما نضطر نخمن، نشوف بالضبط أين تعطل الطلب الحقيقي اللي التطبيق يرسله.
     */
    private fun showDiagnosticDialog(title: String, lines: List<String>) {
        val ctx = context ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val textView = android.widget.TextView(ctx).apply {
                    text = lines.joinToString("\n\n")
                    setPadding(50, 30, 50, 30)
                    setTextIsSelectable(true)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                val scroll = android.widget.ScrollView(ctx).apply { addView(textView) }

                android.app.AlertDialog.Builder(ctx)
                    .setTitle("🔍 $title")
                    .setView(scroll)
                    .setPositiveButton("حسنًا", null)
                    .show()
            } catch (e: Exception) {
                // نتجاهل أي خطأ بعرض النافذة نفسها عشان ما يوقف تشغيل الفيديو
            }
        }
    }
}
