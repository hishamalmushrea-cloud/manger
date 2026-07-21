package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.CommandLogEntity
import com.example.managers.FileHit
import com.example.managers.HealthReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(viewModel: MainViewModel = hiltViewModel()) {
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val currentText by viewModel.currentText.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle(initialValue = emptyList())
    val geminiApiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val isGeminiConfigured by viewModel.isGeminiConfigured.collectAsStateWithLifecycle()
    val learnedCount by viewModel.learnedCount.collectAsStateWithLifecycle()
    val healthReport by viewModel.healthReport.collectAsStateWithLifecycle()
    val continuousEnabled by viewModel.continuousEnabled.collectAsStateWithLifecycle()
    val conversationSeconds by viewModel.conversationSeconds.collectAsStateWithLifecycle()
    val fileHits by viewModel.fileHits.collectAsStateWithLifecycle()
    val awaitingPrompt by viewModel.awaitingPrompt.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showCommandCenter by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hey Manager", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (!isGeminiConfigured) {
                        Text(
                            text = "أضف مفتاح Gemini",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    IconButton(
                        onClick = { showCommandCenter = true },
                        modifier = Modifier.testTag("command_center_button")
                    ) {
                        Icon(Icons.Default.List, contentDescription = "مركز الأوامر")
                    }
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Logs List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = false
            ) {
                items(logs) { log ->
                    LogItem(log)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Text Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = currentText,
                    onValueChange = { viewModel.updateText(it) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_input"),
                    placeholder = { Text("اكتب أمرًا...") },
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { viewModel.submitTextCommand() },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                        .testTag("submit_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // مؤشر «بانتظار ردك» — يظهر أثناء الأسئلة الاستباقية/التصحيح/التعلم
            awaitingPrompt?.let { prompt ->
                AwaitingReplyBanner(question = prompt)
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Mic Button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                MicButton(
                    isListening = isListening,
                    onStart = { viewModel.startListening() },
                    onStop = { viewModel.stopListening() }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    healthReport?.let { report ->
        HealthReportDialog(report = report, onDismiss = { viewModel.dismissHealthReport() })
    }

    fileHits?.let { hits ->
        FileHitsDialog(
            hits = hits,
            onOpen = { viewModel.openFileHit(it) },
            onDismiss = { viewModel.dismissFileHits() }
        )
    }

    if (showCommandCenter) {
        CommandCenterScreen(
            logs = logs,
            onDismiss = { showCommandCenter = false },
            onRerun = { viewModel.rerunCommand(it) },
            onDelete = { viewModel.deleteLog(it.id) },
            onClearAll = { viewModel.clearCommandLog() }
        )
    }

    if (showSettings) {
        SettingsDialog(
            initialGeminiKey = geminiApiKey,
            learnedCount = learnedCount,
            continuousEnabled = continuousEnabled,
            conversationSeconds = conversationSeconds,
            onDismiss = { showSettings = false },
            onSave = { gemini ->
                viewModel.saveGeminiKey(gemini)
                showSettings = false
            },
            onClearLearning = { viewModel.clearLearningData() },
            onContinuousChange = { viewModel.setContinuousEnabled(it) },
            onSecondsChange = { viewModel.setConversationSeconds(it) }
        )
    }
}

@Composable
fun SettingsDialog(
    initialGeminiKey: String,
    learnedCount: Int,
    continuousEnabled: Boolean,
    conversationSeconds: Int,
    onDismiss: () -> Unit,
    onSave: (geminiKey: String) -> Unit,
    onClearLearning: () -> Unit,
    onContinuousChange: (Boolean) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    var geminiKey by remember { mutableStateOf(initialGeminiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الإعدادات") },
        text = {
            Column {
                Text(
                    text = "مفتاح Gemini يفعّل الردود الذكية على أي سؤال (اختياري — الأوامر الأساسية تعمل محلياً بدونه). احصل عليه مجاناً من: aistudio.google.com/apikey",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gemini_key_input"),
                    label = { Text("مفتاح Gemini API") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "كلمة التنبيه: «hey manager» — تعمل بدون إنترنت وبدون أي مفتاح 🎙️",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "🧠 التعلم الذاتي (محلي 100%): التطبيق يتعلم من تصحيحاتك — الأوامر المحفوظة: $learnedCount",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onClearLearning,
                    modifier = Modifier.testTag("clear_learning_button")
                ) {
                    Text(
                        "مسح كل بيانات التعلم",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "المحادثة المستمرة 🎙️ (يبقى يستمع بعد كل أمر)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = continuousEnabled,
                        onCheckedChange = onContinuousChange,
                        modifier = Modifier.testTag("continuous_switch")
                    )
                }
                if (continuousEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "مدة الاستماع بعد كل أمر:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(15, 30, 45, 60).forEach { option ->
                            val selected = option == conversationSeconds
                            TextButton(
                                onClick = { onSecondsChange(option) },
                                modifier = Modifier.testTag("seconds_$option")
                            ) {
                                Text(
                                    "${option}ث",
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(geminiKey) },
                modifier = Modifier.testTag("save_settings_button")
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun MicButton(isListening: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Button(
        onClick = { if (isListening) onStop() else onStart() },
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .testTag("mic_button"),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Microphone",
            modifier = Modifier.size(40.dp),
            tint = Color.White
        )
    }
}

@Composable
fun LogItem(log: CommandLogEntity) {
    val isSuccess = log.status == "SUCCESS"
    val cardColor = if (isSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val textColor = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    val sdf = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = log.commandText,
                fontWeight = FontWeight.Bold,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = log.reason ?: "",
                    color = textColor.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = sdf.format(Date(log.timestamp)),
                    color = textColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}


/** On-screen Device Health report (voice answer plays via TTS in parallel). */
@Composable
fun HealthReportDialog(report: HealthReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📊 تقرير حالة الجهاز", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = report.summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
                report.sections.forEach { section ->
                    Text(
                        text = "${section.emoji} ${section.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    section.lines.forEach { line ->
                        Text(
                            text = "• $line",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_health_report")
            ) {
                Text("إغلاق")
            }
        }
    )
}


/** Clickable on-screen file-search results; tapping a row opens the file. */
@Composable
fun FileHitsDialog(
    hits: List<FileHit>,
    onOpen: (FileHit) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔍 نتائج البحث عن الملفات", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                LazyColumn {
                    items(hits) { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onOpen(hit) }
                                .padding(12.dp)
                                .testTag("file_hit_" + hit.name),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(emojiFor(hit.mime), style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    hit.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2
                                )
                                if (hit.displayPath.isNotBlank()) {
                                    Text(
                                        "في مجلد: " + hit.displayPath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text("↖", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
                Text(
                    "اضغط أي ملف لفتحه مباشرة",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_file_hits")
            ) {
                Text("إغلاق")
            }
        }
    )
}

private fun emojiFor(mime: String): String = when {
    mime.startsWith("image/") -> "🖼️"
    mime.startsWith("video/") -> "🎬"
    mime.startsWith("audio/") -> "🎧"
    mime == "application/pdf" -> "📕"
    mime.contains("word") -> "📝"
    mime.contains("sheet") || mime.contains("excel") -> "📊"
    else -> "📄"
}

/** مؤشر بصري نابض: التطبيق يطرح سؤالاً وينتظر رد المستخدم صوتياً. */
@Composable
fun AwaitingReplyBanner(question: String) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("awaiting_banner"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "❓",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.scale(pulse)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "بانتظار ردك… تكلم الآن 🎤",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    question,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}
