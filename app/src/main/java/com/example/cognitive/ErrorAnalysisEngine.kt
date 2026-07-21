package com.example.cognitive

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * تحليل فشل مهيكل: في أي محطة من سلسلة الفحص انكسر التنفيذ؟
 *
 * @property stage محطة الانكسار: صلاحية / تثبيت / شبكة / قيود النظام / مطابقة / خدمة
 * @property cause جملة السبب المحكية للمستخدم
 * @property solution حل مقترح محكي (يدوية غالباً)
 * @property fixCommand أمر إصلاحي يمكن تنفيذه عبر المسار الصوتي (إن وُجد)
 * @property fixQuestion سؤال تأكيد الإصلاح (محرك التأني قبل أي فعل)
 */
data class ErrorAnalysis(
    val stage: String,
    val cause: String,
    val solution: String?,
    val fixCommand: String?,
    val fixQuestion: String?
)

/**
 * محرك تحليل الخطأ: بدلاً من «فشل التنفيذ» الصامتة، يمشي سلسلة فحص ثابتة —
 * هل توجد الصلاحية؟ هل التطبيق مثبت؟ هل الشبكة متاحة؟ هل النظام يمنع؟ —
 * ثم يقول السبب الحقيقي والحل الأنسب، Offline بالكامل.
 */
@Singleton
class ErrorAnalysisEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun analyze(actionKey: String?, userText: String, rawMessage: String): ErrorAnalysis? {
        return when (actionKey) {
            "call" -> analyzeCall(userText)
            "whatsapp" -> analyzeWhatsApp()
            "sms" -> analyzeSms()
            "search", "youtube", "gemini" -> analyzeOnline()
            "open_app" -> ErrorAnalysis(
                stage = "تثبيت",
                cause = "السبب الأرجح: التطبيق غير مثبت، أو اسمه في جهازك مختلف",
                solution = "انطقه كما يظهر في قائمة تطبيقاتك، أو علّمني اسمه بقول «اسمه يعني كذا»",
                fixCommand = null, fixQuestion = null
            )
            "music" -> ErrorAnalysis(
                stage = "بيانات",
                cause = "السبب الأرجح: لا توجد أغانٍ مفهرسة في الهاتف، أو العنوان غير مطابق",
                solution = "جرّب جزءاً من اسم الأغنية أو اسم الفنان فقط",
                fixCommand = null, fixQuestion = null
            )
            "system" -> ErrorAnalysis(
                stage = "قيود النظام",
                cause = "أندرويد يقيّد تبديل هذا الإعداد من داخل التطبيقات",
                solution = "فتحتُ لك أقرب صفحة إعداد — الحل بنقرة واحدة من إصبعك",
                fixCommand = null, fixQuestion = null
            )
            "alarm" -> ErrorAnalysis(
                stage = "تفكيك",
                cause = "السبب الأرجح: لم أفهم الوقت المطلوب بدقة",
                solution = "جرّب صيغة واضحة: «نبّهني الساعة 6 صباحاً» أو «بعد 10 دقائق»",
                fixCommand = null, fixQuestion = null
            )
            "navigation" -> ErrorAnalysis(
                stage = "خدمة",
                cause = "السبب الأرجح: خدمة إمكانية الوصول غير مفعّلة",
                solution = "فعّلها من إعدادات إمكانية الوصول مرة واحدة، وسأفتحها لك عند أول أمر تنقل",
                fixCommand = null, fixQuestion = null
            )
            else -> powerSaveAnalysis()
        }
    }

    // ---------- سلسلة فحص الاتصال: صلاحية جهات ← صلاحية اتصال ← مطابقة ← نظام ----------

    private fun analyzeCall(userText: String): ErrorAnalysis? {
        val nameBased = !userText.any { it.isDigit() }
        if (nameBased && !granted(Manifest.permission.READ_CONTACTS)) {
            return ErrorAnalysis(
                stage = "صلاحية",
                cause = "السبب الحقيقي: لم تُمنح صلاحية قراءة جهات الاتصال، فلا أستطيع إيجاد الاسم",
                solution = "امنحها من إعدادات التطبيق ثم أعد المحاولة",
                fixCommand = null, fixQuestion = null
            )
        }
        if (!granted(Manifest.permission.CALL_PHONE)) {
            return ErrorAnalysis(
                stage = "صلاحية",
                cause = "صلاحية الاتصال المباشر غير ممنوحة",
                solution = "فتحتُ لك لوحة الاتصال بدلاً منها — أكمل الاتصال بإصبعك",
                fixCommand = null, fixQuestion = null
            )
        }
        if (nameBased) {
            return ErrorAnalysis(
                stage = "مطابقة",
                cause = "السبب الأرجح: لا توجد جهة اتصال بهذا الاسم، أو كتابته مختلفة",
                solution = "انطق الاسم كما هو محفوظ عندك، أو انطق الرقم نفسه",
                fixCommand = null, fixQuestion = null
            )
        }
        return powerSaveAnalysis()
    }

    // ---------- واتساب: تثبيت ← شبكة ← مطابقة ----------

    private fun analyzeWhatsApp(): ErrorAnalysis {
        if (!installed("com.whatsapp") && !installed("com.whatsapp.w4b")) {
            return ErrorAnalysis(
                stage = "تثبيت",
                cause = "السبب الحقيقي: واتساب غير مثبت على هذا الجهاز (أو غير ظاهر للتطبيق)",
                solution = "ثبّته ثم أعد المحاولة",
                fixCommand = "ابحث عن واتساب",
                fixQuestion = "أبحث لك عن واتساب في المتصفح من الآن؟"
            )
        }
        if (!online()) {
            return ErrorAnalysis(
                stage = "شبكة",
                cause = "لا يوجد اتصال إنترنت الآن — تجهيز المحادثة ممكن لكن الإرسال يحتاج شبكة",
                solution = "فعّل البيانات أو الواي فاي ثم أعد المحاولة",
                fixCommand = null, fixQuestion = null
            )
        }
        return ErrorAnalysis(
            stage = "مطابقة",
            cause = "السبب الأرجح: الرقم غير مسجل في واتساب، أو الاسم غير مطابق",
            solution = "تحقق من الرقم بصيغته اليمنية، أو طابق الاسم مع جهات اتصالك",
            fixCommand = null, fixQuestion = null
        )
    }

    private fun analyzeSms(): ErrorAnalysis {
        if (!granted(Manifest.permission.SEND_SMS)) {
            return ErrorAnalysis(
                stage = "صلاحية",
                cause = "السبب الحقيقي: صلاحية إرسال الرسائل غير ممنوحة",
                solution = "امنحها من إعدادات التطبيق",
                fixCommand = null, fixQuestion = null
            )
        }
        return ErrorAnalysis(
            stage = "شبكة",
            cause = "السبب الأرجح: لا توجد تغطية شبكة خلوية حالياً",
            solution = "أعد المحاولة عند توفر إشارة",
            fixCommand = null, fixQuestion = null
        )
    }

    private fun analyzeOnline(): ErrorAnalysis {
        if (!online()) {
            return ErrorAnalysis(
                stage = "شبكة",
                cause = "السبب الحقيقي: لا يوجد اتصال إنترنت — البحث واليوتيوب وجيميناي يحتاجونه",
                solution = "فعّل البيانات أو الواي فاي ثم أعد المحاولة",
                fixCommand = null, fixQuestion = null
            )
        }
        return powerSaveAnalysis() ?: ErrorAnalysis(
            stage = "خدمة",
            cause = "السبب الأرجح: الخدمة الخارجية مشغولة حالياً",
            solution = "أعد المحاولة بعد قليل",
            fixCommand = null, fixQuestion = null
        )
    }

    private fun powerSaveAnalysis(): ErrorAnalysis? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode) {
                ErrorAnalysis(
                    stage = "قيود النظام",
                    cause = "وضع توفير الطاقة نشط ويقيّد الخلفية والأداء",
                    solution = "أطفئه مؤقتاً ثم أعد المحاولة",
                    fixCommand = null, fixQuestion = null
                )
            } else null
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

    /** فحص الشبكة بأمان: بلا صلاحية قراءة الحالة لا نتهم أحداً بالانقطاع. */
    private fun online(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork != null
    } catch (e: Exception) {
        true
    }
}
