package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.service.SpeechListenerService
import com.example.ui.AppScreen
import com.example.ui.BatteryOptimizationHelper
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val showBatteryDialog = remember { mutableStateOf(false) }

                val permissions = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                }

                val permissionState = rememberMultiplePermissionsState(permissions)

                LaunchedEffect(Unit) {
                    if (!permissionState.allPermissionsGranted) {
                        permissionState.launchMultiplePermissionRequest()
                    }
                    if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@MainActivity)) {
                        showBatteryDialog.value = true
                    }
                    if (!Settings.System.canWrite(this@MainActivity)) {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }

                    // Start Foreground Service for Wake Word Listening
                    val serviceIntent = Intent(this@MainActivity, SpeechListenerService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }

                if (showBatteryDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showBatteryDialog.value = false },
                        title = { Text("تحسين البطارية") },
                        text = { Text("يحتاج التطبيق للعمل في الخلفية لتنفيذ الأوامر المجدولة والاستماع للكلمة التنبيهية بدون توقف. يرجى السماح باستثناء التطبيق من تحسينات البطارية لضمان عمله بشكل صحيح.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showBatteryDialog.value = false
                                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this@MainActivity)
                            }) {
                                Text("السماح")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBatteryDialog.value = false }) {
                                Text("إلغاء")
                            }
                        }
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen()
                }
            }
        }
    }
}
// Just checking if NotificationListener is enabled.
