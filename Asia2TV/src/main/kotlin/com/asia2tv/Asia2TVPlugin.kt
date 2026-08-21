package com.asia2tv

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

                    CoroutineScope(Dispatchers.IO).launch {
                        val result = Asia2TVAuth.login(ctx)
                        withContext(Dispatchers.Main) {
                            val icon = if (result.success) "✅" else "❌"
                            Toast.makeText(
                                ctx,
                                "$icon ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}
