package com.example.cognitive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * محلل الأسباب عند الفشل: بدلاً من «فشل الأمر» الصامتة،
 * يفحص الأدلة المتاحة Offline (الأذونات، التثبيت، وضع توفير الطاقة)
 * ويخبر المستخدم بالسبب الأرجح وكيف يصلحه.
 */
@Singleton
class FailureDiagnoser @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** جملة تشخيصية قصيرة تُلحق برسالة الفشل، أو null إن لم يوجد دليل واضح. */
    fun diagnose(actionKey: String?, userText: String): String? {
        return when (actionKey) {
            "call" -> diagnoseCall(userText)
            "whatsapp" -> diagnoseWhatsApp()
            "sms" -> diagnoseSms()
            "open_app" -> "السبب الأرجح: التطبيق غير مثبت، أو اسمه في جهازك مختلف — جرّب اسماً آخر له"
            "music" -> "السبب الأرجح: لا توجد أغانٍ مفهرسة في الهاتف، أو العنوان غير مطابق"
            else -> powerSaveHint()
        }
    }

    private fun diagnoseCall(userText: String): String? {
        val hasDigits = userText.any { it.isDigit() }
        if (!hasDigits && !granted(Manifest.permission.READ_CONTACTS)) {
            return "السبب الأرجح: صلاحية جهات الاتصال غير ممنوحة — امنحها من إعدادات التطبيق"
        }
        if (!granted(Manifest.permission.CALL_PHONE)) {
            return "ملاحظة: صلاحية الاتصال المباشر غير ممنوحة، لذلك فتحتُ لوحة الاتصال بدلاً منها"
        }
        return null
    }

    private fun diagnoseWhatsApp(): String? {
        if (!installed("com.whatsapp") && !installed("com.whatsapp.w4b")) {
            return "السبب الأرجح: واتساب غير مثبت على هذا الجهاز (أو غير ظاهر للتطبيق)"
        }
        return "تحقق أن الرقم مسجل في واتساب، وأن الاسم مطابق لجهات اتصالك"
    }

    private fun diagnoseSms(): String? {
        if (!granted(Manifest.permission.SEND_SMS)) {
            return "السبب الأرجح: صلاحية إرسال الرسائل غير ممنوحة — امنحها من الإعدادات"
        }
        return null
    }

    private fun powerSaveHint(): String? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode) "تنبيه: وضع توفير الطاقة نشط وقد يقيّد بعض الميزات" else null
        } catch (e: Exception) {
            null
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
