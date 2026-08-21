package com.asia2tv

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.app.AlertDialog
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Asia2TVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Asia2TV(context))

        openSettings = { ctx ->
            val prefs = ctx.getSharedPreferences("asia2tv_prefs", Context.MODE_PRIVATE)

            val emailInput = EditText(ctx).apply {
                hint = "البريد الإلكتروني"
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
                .setPositiveButton("حفظ") { _, _ ->
                    prefs.edit()
                        .putString("asia2tv_email", emailInput.text.toString().trim())
                        .putString("asia2tv_password", passInput.text.toString())
                        // نمسح أي جلسة قديمة عشان يعيد تسجيل الدخول بالبيانات الجديدة
                        .remove("asia2tv_cookies")
                        .apply()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}
