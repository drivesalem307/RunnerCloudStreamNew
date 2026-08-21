package com.asia2tv

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.concurrent.thread

@CloudstreamPlugin
class Asia2TVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Asia2TV(context))

        openSettings = { ctx ->
            val prefs = ctx.getSharedPreferences("asia2tv_prefs", Context.MODE_PRIVATE)

            val emailInput = EditText(ctx).apply {
                hint = "اسم المستخدم (وليس بالضرورة البريد الإلكتروني)"
                setText(prefs.getString("asia2tv_email", ""))
            }
            val passInput = EditText(ctx).apply {
                hint = "كلمة المرور"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(prefs.getString("asia2tv_password", ""))
            }

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 10)
                addView(emailInput)
                addView(passInput)
            }

            AlertDialog.Builder(ctx)
                .setTitle("تسجيل الدخول - Asia2TV")
                .setView(layout)
                .setPositiveButton("حفظ واختبار الدخول") { _, _ ->
                    prefs.edit()
                        .putString("asia2tv_email", emailInput.text.toString().trim())
                        .putString("asia2tv_password", passInput.text.toString())
                        .apply()

                    // نمسح أي جلسة قديمة عشان نجبره يسجل دخول من جديد بالبيانات الجديدة
                    Asia2TVAuth.clearCookies(ctx)

                    Toast.makeText(ctx, "جاري تسجيل الدخول...", Toast.LENGTH_SHORT).show()

                    // نشغّل دالة تسجيل الدخول (suspend) بخيط منفصل، بدون الحاجة لمكتبة kotlinx.coroutines
                    val continuation = object : Continuation<Asia2TVLoginResult> {
                        override val context = EmptyCoroutineContext
                        override fun resumeWith(result: Result<Asia2TVLoginResult>) {
                            val loginResult = result.getOrNull()
                            val message = loginResult?.message
                                ?: "خطأ غير متوقع: ${result.exceptionOrNull()?.message}"
                            val icon = if (loginResult?.success == true) "✅" else "❌"

                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(ctx, "$icon $message", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    thread {
                        val block: suspend () -> Asia2TVLoginResult = { Asia2TVAuth.login(ctx) }
                        block.startCoroutine(continuation)
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}
