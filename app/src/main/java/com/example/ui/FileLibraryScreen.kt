package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.FileIndexEntity

/**
 * شاشة «مكتبة الملفات المفهرسة» — نافذة شفافية تُشعر المستخدم أن المساعد
 * يعرف جهازه فعلاً، وتعطيه ثلاث قوى:
 *
 *  1) يرى ما فهرَسَه التطبيق (عدد الصوتيات/الصور/الفيديوهات/المستندات).
 *  2) يخفي أي ملف من نتائج البحث الصوتي (خصوصية بيد المستخدم 👆).
 *  3) يضيف مجلداً من اختياره (SAF) — مثل مجلد «المفضلة» الذي لا تصله
 *     فهارس الوسائط العامة — فيصبح محتواه قابلاً للاستدعاء صوتياً.
 *
 * تذكير مطمئن دائم أسفل الشاشة: كل هذا يعيش على جهازك فقط.
 */
@Composable
fun FileLibraryDialog(
    files: List<FileIndexEntity>,
    indexingBusy: Boolean,
    onRefresh: () -> Unit,
    onAddFolder: () -> Unit,
    onClearFixes: () -> Unit,
    onToggleHidden: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var filter by remember { mutableStateOf("") }

    val counts = remember(files) {
        mapOf(
            "أغاني" to files.count { it.kind == "AUDIO" },
            "صور" to files.count { it.kind == "IMAGE" },
            "فيديو" to files.count { it.kind == "VIDEO" },
            "مستندات" to files.count { it.kind == "PDF" || it.kind == "WORD" || it.kind == "EXCEL" || it.kind == "OTHER" }
        )
    }
    val visible = remember(files, filter) {
        val f = filter.trim()
        val base = if (f.isEmpty()) files else files.filter {
            it.name.contains(f, true) || it.folder.contains(f, true) || it.artist.contains(f, true)
        }
        base.take(300) // سقف العرض: القوائم الأطول تُدار بالبحث الصوتي لا بالتمرير
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("مكتبة الملفات المفهرسة")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ملخص العدادات
                Text(
                    counts.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // أزرار التحكم
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = onRefresh, enabled = !indexingBusy) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (indexingBusy) "جارِ الفحص..." else "إعادة الفهرسة")
                    }
                    OutlinedButton(onClick = onAddFolder) {
                        Text("＋ مجلد")
                    }
                    TextButton(onClick = onClearFixes) {
                        Text("نسيان اختياراتي")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // مرشّر نصي سريع
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("رشّح بالاسم أو المجلد أو الفنان...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(visible, key = { it.uriString }) { file ->
                        IndexedFileRow(file = file, onToggleHidden = onToggleHidden)
                        HorizontalDivider()
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "كل الفهرسة تتم على جهازك فقط — لا يغادر أي شيء إلى الإنترنت.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

@Composable
private fun IndexedFileRow(
    file: FileIndexEntity,
    onToggleHidden: (String, Boolean) -> Unit
) {
    val kindLabel = when (file.kind) {
        "AUDIO" -> "🎵"
        "IMAGE" -> "🖼️"
        "VIDEO" -> "🎬"
        else -> "📄"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(kindLabel, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            val sub = buildList {
                if (file.artist.isNotBlank()) add(file.artist)
                if (file.folder.isNotBlank()) add("مجلد: ${file.folder}")
                if (file.playCount > 0) add("شُغّل ${file.playCount}×")
            }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { onToggleHidden(file.uriString, !file.hidden) }) {
            Icon(
                imageVector = if (file.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (file.hidden) "مخفي من البحث — إظهار" else "ظاهر — إخفاء من البحث",
                tint = if (file.hidden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
