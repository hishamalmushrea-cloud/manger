package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.CommandLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(viewModel: MainViewModel = hiltViewModel()) {
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val currentText by viewModel.currentText.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hey Manager", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { /* Open Settings or Accessibility Info */ }) {
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
