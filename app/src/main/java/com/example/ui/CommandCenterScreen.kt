package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.CommandLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CommandLogFilter { ALL, SUCCESS, FAILED }

/**
 * 🎯 مركز الأوامر — شاشة كاملة لسجل كل ما نفّذه المساعد:
 * بحث، تصفية ناجح/فاشل، إعادة تنفيذ بضغطة، حذف مفرد، مسح كامل، وإحصائيات.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandCenterScreen(
    logs: List<CommandLogEntity>,
    onDismiss: () -> Unit,
    onRerun: (CommandLogEntity) -> Unit,
    onDelete: (CommandLogEntity) -> Unit,
    onClearAll: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CommandLogFilter.ALL) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // ---------------- Statistics ----------------
    val total = logs.size
    val successCount = logs.count { it.status == "SUCCESS" }
    val failedCount = total - successCount
    val successRate = if (total > 0) successCount * 100 / total else 0

    // ---------------- Search + filter ----------------
    val trimmedQuery = query.trim()
    val filtered = logs.filter { log ->
        val matchesFilter = when (filter) {
            CommandLogFilter.ALL -> true
            CommandLogFilter.SUCCESS -> log.status == "SUCCESS"
            CommandLogFilter.FAILED -> log.status != "SUCCESS"
        }
        val matchesQuery = trimmedQuery.isEmpty() ||
            log.commandText.contains(trimmedQuery, ignoreCase = true) ||
            (log.understoodCommand ?: "").contains(trimmedQuery, ignoreCase = true) ||
            (log.reason ?: "").contains(trimmedQuery, ignoreCase = true) ||
            (log.actionTaken ?: "").contains(trimmedQuery, ignoreCase = true)
        matchesFilter && matchesQuery
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("command_center_screen"),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                // -------- Header --------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🎯 مركز الأوامر",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "سجل كامل على جهازك فقط — بدون إنترنت",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { if (logs.isNotEmpty()) showClearConfirm = true },
                        modifier = Modifier.testTag("clear_all_logs_button")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "مسح السجل بالكامل",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_command_center")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // -------- Statistics card --------
                StatsCard(
                    total = total,
                    successCount = successCount,
                    failedCount = failedCount,
                    successRate = successRate
                )

                Spacer(modifier = Modifier.height(12.dp))

                // -------- Search field --------
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("command_search_input"),
                    placeholder = { Text("ابحث في السجل...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "مسح البحث")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // -------- Filters --------
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = filter == CommandLogFilter.ALL,
                        onClick = { filter = CommandLogFilter.ALL },
                        label = { Text("الكل ($total)") },
                        modifier = Modifier.testTag("filter_all")
                    )
                    FilterChip(
                        selected = filter == CommandLogFilter.SUCCESS,
                        onClick = { filter = CommandLogFilter.SUCCESS },
                        label = { Text("✅ الناجحة ($successCount)") },
                        modifier = Modifier.testTag("filter_success")
                    )
                    FilterChip(
                        selected = filter == CommandLogFilter.FAILED,
                        onClick = { filter = CommandLogFilter.FAILED },
                        label = { Text("❌ الفاشلة ($failedCount)") },
                        modifier = Modifier.testTag("filter_failed")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // -------- Log list --------
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (logs.isEmpty())
                                "لا يوجد أي أوامر بعد 🎙️\nقل «hey manager» وأصدر أمرك الأول"
                            else
                                "لا توجد نتائج مطابقة للبحث أو التصفية",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(filtered, key = { it.id }) { log ->
                            CommandCenterItem(
                                log = log,
                                onRerun = { onRerun(log) },
                                onDelete = { onDelete(log) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    // -------- Clear-all confirmation --------
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("مسح السجل بالكامل؟") },
            text = { Text("سيتم حذف جميع الأوامر المسجلة ($total) نهائياً ولا يمكن التراجع.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearAll()
                    },
                    modifier = Modifier.testTag("confirm_clear_all")
                ) {
                    Text("مسح الكل", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("إلغاء") }
            }
        )
    }
}

// ------------------------------------------------------------------
// Statistics card
// ------------------------------------------------------------------

@Composable
private fun StatsCard(
    total: Int,
    successCount: Int,
    failedCount: Int,
    successRate: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(emoji = "📟", value = "$total", label = "أمر منفذ")
                StatItem(emoji = "✅", value = "$successCount", label = "ناجح")
                StatItem(emoji = "❌", value = "$failedCount", label = "فاشل")
                StatItem(emoji = "📈", value = "$successRate٪", label = "نسبة النجاح")
            }
            Spacer(modifier = Modifier.height(10.dp))
            // شريط نسبة النجاح
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (successRate.coerceIn(0, 100) / 100f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (successRate >= 70) Color(0xFF2E7D32)
                            else MaterialTheme.colorScheme.error
                        )
                )
            }
        }
    }
}

@Composable
private fun StatItem(emoji: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------
// Single log entry
// ------------------------------------------------------------------

@Composable
private fun CommandCenterItem(
    log: CommandLogEntity,
    onRerun: () -> Unit,
    onDelete: () -> Unit
) {
    val isSuccess = log.status == "SUCCESS"
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy - hh:mm:ss a", Locale.getDefault()) }
    val confidencePercent = (log.confidence * 100).toInt().coerceIn(0, 100)
    val confidenceColor = when {
        confidencePercent >= 80 -> Color(0xFF2E7D32)
        confidencePercent >= 50 -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("command_log_item_" + log.id),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ــ النص الذي قاله المستخدم + حالة النجاح ــ
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isSuccess) "✅" else "❌",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = log.commandText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            // ــ تاريخ ووقت التنفيذ ــ
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "🕐 " + dateFormat.format(Date(log.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ــ الأمر الذي فهمه التطبيق ــ
            if (!log.understoodCommand.isNullOrBlank() && log.understoodCommand != log.commandText) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "🧠 فهمه التطبيق: «" + log.understoodCommand + "»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ــ شارات: الإجراء المنفذ + نسبة الثقة + وقت التنفيذ ــ
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!log.actionTaken.isNullOrBlank()) {
                    InfoBadge(text = "⚙️ " + log.actionTaken)
                }
                InfoBadge(text = "الثقة $confidencePercent٪", color = confidenceColor)
            }
            if (log.durationMs > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row {
                    InfoBadge(text = "⏱ وقت التنفيذ: " + formatDuration(log.durationMs))
                }
            }

            // ــ رد المساعدة / سبب الفشل ــ
            if (isSuccess) {
                if (!log.reason.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = log.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "سبب الفشل: " + (log.failReason ?: log.reason ?: "غير معروف"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // ــ أزرار: إعادة التنفيذ + حذف ــ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onRerun,
                    modifier = Modifier.testTag("rerun_" + log.id)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("إعادة التنفيذ")
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_" + log.id)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun InfoBadge(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** تنسيق مدة التنفيذ: فوري / مللي ث / ثانية كسرية. */
private fun formatDuration(ms: Long): String = when {
    ms <= 0L -> "فوري"
    ms < 1000L -> "$ms مللي ث"
    else -> {
        val seconds = ms / 1000.0
        val rounded = (seconds * 10).toLong() / 10.0
        "$rounded ث"
    }
}
