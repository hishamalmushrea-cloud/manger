package com.example.cognitive

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * محرك التفكير السببي: لا يجيب بعموميات بل يمشي سلسلة فحص علّية —
 * حرارة؟ ← ذاكرة؟ ← تخزين؟ ← صحة بطارية —
 * يقرأ مؤشرات حقيقية من النظام (Offline) ثم يعلن السبب الأرجح بصراحة.
 * لا يخترع أسماء تطبيقات: يقيس الضغط، ويقول ما وجده فعلاً فقط.
 */
@Singleton
class CausalEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** تشخيص سببي متسلسل لبطء الجهاز، بلغة يفهمها أي مستخدم. */
    fun slownessReport(): String {
        val evidence = mutableListOf<String>()
        val causes = mutableListOf<String>()

        // ---------- 1) الحرارة وصحة البطارية ----------
        var batteryHealthText = "غير معروفة"
        try {
            val bi = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (bi != null) {
                val tempTenths = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (tempTenths > 0) {
                    val celsius = tempTenths / 10.0
                    evidence.add("الحرارة $celsius درجة")
                    when {
                        celsius >= 45 -> causes.add(
                            "حرارة الهاتف مرتفعة جداً ($celsius درجة) — أبعده عن الشمس وأزل الغطاء وأغلق الألعاب الثقيلة"
                        )
                        celsius >= 42 -> causes.add(
                            "حرارة الهاتف مرتفعة قليلاً ($celsius درجة) — امنحه دقائق راحة"
                        )
                    }
                }
                batteryHealthText = when (bi.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "جيدة"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "تعاني سخونة"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "متهالكة وتحتاج استبدالاً"
                    BatteryManager.BATTERY_HEALTH_COLD -> "باردة"
                    else -> "غير معروفة"
                }
            }
        } catch (e: Exception) {
            // قراءة البطارية اختيارية — غيابها لا يوقف التشخيص
        }

        // ---------- 2) الذاكرة RAM ----------
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val usedPct = ((memInfo.totalMem - memInfo.availMem) * 100 / memInfo.totalMem).toInt()
            evidence.add("الذاكرة مستخدمة $usedPct%")
            if (memInfo.lowMemory || usedPct >= 85) {
                causes.add(
                    "الذاكرة شبه ممتلئة ($usedPct%) — أغلق التطبيقات الأخيرة بزر المربعات، وأعد تشغيل الهاتف إن استمر البطء"
                )
            }
        } catch (e: Exception) {
            // اختياري
        }

        // ---------- 3) التخزين ----------
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freePct = (stat.availableBytes * 100 / stat.totalBytes).toInt()
            evidence.add("التخزين المتاح $freePct%")
            if (freePct <= 12) {
                causes.add(
                    "التخزين شبه ممتلئ (المتاح $freePct% فقط) — احذف ملفات كبيرة أو انقلها، فالنظام يتباطأ بشدة مع الامتلاء"
                )
            }
        } catch (e: Exception) {
            // اختياري
        }

        // ---------- الحكم السببي النهائي ----------
        val evidenceText = if (evidence.isEmpty()) "قراءات جزئية" else evidence.joinToString("، ")
        return if (causes.isEmpty()) {
            "فحصت جهازك بعمق: $evidenceText، وصحة البطارية $batteryHealthText. " +
                "كل المؤشرات طبيعية — البطء الحالي غالباً سببه مؤقت، " +
                "جرّب إعادة تشغيل الهاتف إن استمر"
        } else {
            "فحصت جهازك بعمق: $evidenceText، وصحة البطارية $batteryHealthText. " +
                "التشخيص السببي: " + causes.joinToString("، وأيضاً ")
        }
    }
}
