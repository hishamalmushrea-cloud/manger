package com.example.processor

import com.example.managers.DeviceHealthManager
import com.example.managers.HealthReport
import com.example.managers.HealthSection
import javax.inject.Inject

/**
 * Device Health Monitor — voice + on-screen report:
 *  - «كيف أداء الهاتف؟» / «حالة الجهاز» (full report)
 *  - «كم بقي من البطارية؟» / «صحة البطارية»
 *  - «هل حرارة الهاتف مرتفعة؟»
 *  - «كم مساحة التخزين المتبقية؟» / «كم الذاكرة؟» (RAM)
 *  - «منذ متى الجهاز شغال؟» (uptime)
 *  - «ما أكثر تطبيق يستهلك البطارية؟» → opens the system battery screen
 *    (per-app stats are restricted by Android itself)
 */
class HealthStrategy @Inject constructor(
    private val health: DeviceHealthManager
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        return isBatteryTopQ(command) || isMemTopQ(command) || isTempQ(command) ||
                isRamQ(command) || isStorageQ(command) || isUptimeQ(command) ||
                isBatteryRichQ(command) || isFullReportQ(command)
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val (bat, mem, sto) = Triple(health.battery(), health.memory(), health.storage())

        return when {
            isBatteryTopQ(command) -> batteryTopConsumers(bat)
            isMemTopQ(command) -> memTopConsumers(mem)
            isTempQ(command) -> temperatureAnswer(bat)
            isRamQ(command) -> ramAnswer(mem)
            isStorageQ(command) -> storageAnswer(sto)
            isUptimeQ(command) -> uptimeAnswer()
            isBatteryRichQ(command) -> batteryAnswer(bat)
            else -> fullReport(bat, mem, sto)
        }
    }

    // ------------------------------------------------------------------
    // Question types
    // ------------------------------------------------------------------

    private fun isBatteryTopQ(c: String) =
        c.containsAny("استهلاك", "تستهلك", "يمتص", "أكثر", "اكثر") && c.contains("بطارية") && !isBatteryRichQ(c)

    private fun isMemTopQ(c: String) =
        c.containsAny("استهلاك", "تستهلك", "يمتص", "أكثر", "اكثر") && c.containsAny("ذاكرة", "رام", "ram")

    private fun isTempQ(c: String) = c.containsAny("حرارة", "سخونة", "يسخن", "temperature", "حرارته")

    private fun isRamQ(c: String) = c.containsAny("ذاكرة", "رام", "ram", "ميموري") &&
            !c.contains("تخزين") && !c.contains("الذاكرة الداخلية")

    private fun isStorageQ(c: String) = c.containsAny("تخزين", "مساحة", "مساحه", "storage", "ذاكرة داخلية")

    private fun isUptimeQ(c: String) = c.containsAny(
        "مدة تشغيل", "مدة التشغيل", "يعمل منذ", "شغال منذ", "عامل منذ",
        "منذ متى الجهاز", "منذ متى الهاتف", "آخر إعادة تشغيل", "اخر اعادة تشغيل", "uptime"
    )

    private fun isBatteryRichQ(c: String) =
        c.containsAny("بطارية", "البطارية") && c.containsAny("بقي", "باقي", "متبقي", "صحة", "حرارة", "كيف")

    private fun isFullReportQ(c: String) = c.containsAny(
        "أداء", "اداء", "حالة الهاتف", "حالة الجهاز", "حالة النظام",
        "صحة الجهاز", "صحة الهاتف", "performance"
    )

    private fun String.containsAny(vararg words: String) = words.any { contains(it) }

    // ------------------------------------------------------------------
    // Answers
    // ------------------------------------------------------------------

    private fun fullReport(
        bat: DeviceHealthManager.BatteryStat,
        mem: DeviceHealthManager.MemoryStat,
        sto: DeviceHealthManager.StorageStat
    ): CommandResult {
        val (tempVerdict, isHot) = health.temperatureVerdict(bat.temperatureC)

        val problems = mutableListOf<String>()
        if (isHot) problems.add("الحرارة مرتفعة (${fmtTemp(bat.temperatureC)})")
        if (bat.levelPct in 0..14 && !bat.charging) problems.add("البطارية منخفضة (${bat.levelPct}%)")
        if (mem.usedPct >= 85 || mem.isLow) problems.add("الذاكرة شبه ممتلئة (${mem.usedPct}%)")
        if (sto.usedPct >= 90) problems.add("التخزين شبه ممتلئ (${sto.usedPct}%)")

        val verdict = if (problems.isEmpty()) "أداء هاتفك جيد وكل شيء طبيعي."
        else "هاتفك يحتاج انتباهاً: ${problems.joinToString("، ")}."

        val summary = "$verdict البطارية ${bat.levelPct}% (${bat.statusText})، " +
                "الحرارة ${fmtTemp(bat.temperatureC)} $tempVerdict، " +
                "الذاكرة مستخدمة ${mem.usedPct}%، " +
                "التخزين المتبقي ${health.formatGb(sto.freeBytes)} جيجا من ${health.formatGb(sto.totalBytes)}، " +
                "والجهاز يعمل منذ ${health.formatUptime(health.uptimeMillis())}."

        val sections = listOf(
            batterySection(bat),
            memorySection(mem),
            storageSection(sto),
            uptimeSection()
        )
        return CommandResult(true, summary, healthReport = HealthReport(summary, sections))
    }

    private fun temperatureAnswer(bat: DeviceHealthManager.BatteryStat): CommandResult {
        val (verdict, isHot) = health.temperatureVerdict(bat.temperatureC)
        val answer = if (bat.temperatureC < 0) "تعذر قياس حرارة جهازك"
        else when {
            isHot -> "نعم! حرارة هاتفك مرتفعة: ${fmtTemp(bat.temperatureC)} — أغلق التطبيقات الثقيلة وأبعده عن الشمس"
            verdict == "طبيعية" -> "لا، الحرارة طبيعية: ${fmtTemp(bat.temperatureC)}"
            else -> "الحرارة ${fmtTemp(bat.temperatureC)} — $verdict لكنها غير مقلقة"
        }
        return CommandResult(true, answer, healthReport = HealthReport(answer, listOf(batterySection(bat))))
    }

    private fun ramAnswer(mem: DeviceHealthManager.MemoryStat): CommandResult {
        val state = if (mem.isLow || mem.usedPct >= 90) " — الذاكرة تحت ضغط، أغلق بعض التطبيقات" else " — الوضع جيد"
        val summary = "ذاكرة الوصول: مستخدم ${mem.usedPct}%، المتاح ${health.formatGb(mem.availBytes)} " +
                "من ${health.formatGb(mem.totalBytes)} جيجا$state"
        return CommandResult(true, summary, healthReport = HealthReport(summary, listOf(memorySection(mem))))
    }

    private fun storageAnswer(sto: DeviceHealthManager.StorageStat): CommandResult {
        val state = if (sto.usedPct >= 90) " — التخزين شبه ممتلئ! امسح ملفات قديمة" else ""
        val summary = "التخزين: المتبقي ${health.formatGb(sto.freeBytes)} جيجا من أصل " +
                "${health.formatGb(sto.totalBytes)} جيجا (مستخدم ${sto.usedPct}%)$state"
        return CommandResult(true, summary, healthReport = HealthReport(summary, listOf(storageSection(sto))))
    }

    private fun uptimeAnswer(): CommandResult {
        val summary = "الجهاز يعمل بلا انقطاع منذ ${health.formatUptime(health.uptimeMillis())}"
        return CommandResult(true, summary, healthReport = HealthReport(summary, listOf(uptimeSection())))
    }

    private fun batteryAnswer(bat: DeviceHealthManager.BatteryStat): CommandResult {
        val summary = "البطارية ${bat.levelPct}% (${bat.statusText})، " +
                "حرارتها ${fmtTemp(bat.temperatureC)} وحالتها ${bat.healthText}"
        return CommandResult(true, summary, healthReport = HealthReport(summary, listOf(batterySection(bat))))
    }

    /** Android forbids apps from reading per-app battery stats → system screen. */
    private fun batteryTopConsumers(bat: DeviceHealthManager.BatteryStat): CommandResult {
        val opened = health.openBatteryUsageScreen()
        val summary = if (opened) {
            "أندرويد لا يسمح للتطبيقات بقراءة استهلاك البطارية لكل تطبيق حمايةً لخصوصيتك، " +
                    "لكن فتحت لك شاشة استهلاك البطارية الرسمية وستجد أكثر المستهلكين في الأعلى"
        } else {
            "أندرويد يمنع قراءة استهلاك البطارية لكل تطبيق — افتح الإعدادات ثم البطارية ثم استخدام البطارية"
        }
        return CommandResult(true, summary, healthReport = HealthReport(summary, listOf(batterySection(bat))))
    }

    private fun memTopConsumers(mem: DeviceHealthManager.MemoryStat): CommandResult {
        val summary = "استهلاك الذاكرة لكل تطبيق محمي من نظام أندرويد ولا يُتاح للتطبيقات، " +
                "لكن مستوى الذاكرة الكلي الآن ${mem.usedPct}% مستخدم"
        return CommandResult(true, summary, healthReport = HealthReport(summary, listOf(memorySection(mem))))
    }

    // ------------------------------------------------------------------
    // Report sections
    // ------------------------------------------------------------------

    private fun batterySection(bat: DeviceHealthManager.BatteryStat) = HealthSection(
        "🔋", "البطارية",
        listOf(
            "النسبة: ${bat.levelPct}%",
            "الحالة: ${bat.statusText}",
            "الحرارة: ${fmtTemp(bat.temperatureC)} (${health.temperatureVerdict(bat.temperatureC).first})",
            "الصحة: ${bat.healthText}"
        )
    )

    private fun memorySection(mem: DeviceHealthManager.MemoryStat) = HealthSection(
        "🧠", "الذاكرة (RAM)",
        listOf(
            "المستخدم: ${mem.usedPct}%",
            "المتاح: ${health.formatGb(mem.availBytes)} جيجا",
            "الإجمالي: ${health.formatGb(mem.totalBytes)} جيجا",
            "الوضع: " + if (mem.isLow) "تحت ضغط ⚠️" else "جيد ✅"
        )
    )

    private fun storageSection(sto: DeviceHealthManager.StorageStat) = HealthSection(
        "💾", "التخزين",
        listOf(
            "المتبقي: ${health.formatGb(sto.freeBytes)} جيجا",
            "الإجمالي: ${health.formatGb(sto.totalBytes)} جيجا",
            "المستخدم: ${sto.usedPct}%"
        )
    )

    private fun uptimeSection() = HealthSection(
        "⏱️", "مدة التشغيل",
        listOf("يعمل منذ: ${health.formatUptime(health.uptimeMillis())}")
    )

    private fun fmtTemp(c: Float): String =
        if (c < 0) "غير متاحة" else String.format(java.util.Locale.US, "%.1f°م", c)
}

