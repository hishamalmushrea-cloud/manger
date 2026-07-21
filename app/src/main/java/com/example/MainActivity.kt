package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
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
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /** Extra set by SpeechListenerService when the wake word is spoken while
         *  the activity was not in the foreground — triggers voice capture. */
        const val EXTRA_WAKE_COMMAND = "com.example.extra.WAKE_COMMAND"
    }

    private val viewModel: MainViewModel by viewModels()

    /** Starts microphone listening when the wake word service fires. */
    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SpeechListenerService.ACTION_WAKE_WORD) {
                Timber.d("Wake word broadcast received — starting speech recognition")
                viewModel.startListening()
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ContextCompat.registerReceiver(
            this,
            wakeWordReceiver,
            IntentFilter(SpeechListenerService.ACTION_WAKE_WORD),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MyApplicationTheme {
                val showBatteryDialog = remember { mutableStateOf(false) }

                val permissions = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_CONTACTS,
                    // Call-log access enables «اتصل بآخر رقم اتصل بي» commands.
                    Manifest.permission.READ_CALL_LOG
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    // Media library access for music + smart file search (Android 13+).
                    permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                    permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                    permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    // Music library access on Android 12 and below.
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
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
                    // "Display over other apps" exempts us from Android's background
                    // activity-launch blocking — without it «hey manager» can't open
                    // apps while Hey Manager itself is in the background.
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeIntent(intent)
    }

    /** Wake word spoken while the app was backgrounded: start listening right away. */
    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_COMMAND, false) == true) {
            intent.removeExtra(EXTRA_WAKE_COMMAND) // don't re-trigger on recreate
            Timber.d("Wake intent received — starting speech recognition")
            viewModel.startListening()
        }
    }

    override fun onResume() {
        super.onResume()
        handleWakeIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(wakeWordReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered — safe to ignore
        }
    }
}
// Just checking if NotificationListener is enabled.
