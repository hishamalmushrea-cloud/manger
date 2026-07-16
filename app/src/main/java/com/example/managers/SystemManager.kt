package com.example.managers

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun setWifiState(enabled: Boolean): Boolean {
        return try {
            // Note: Since Android 10, changing Wifi state via WifiManager is deprecated and returns false.
            // We should open the settings panel if we can't change it.
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val success = wifiManager.setWifiEnabled(enabled)
            if (!success) {
                openSettings(Settings.ACTION_WIFI_SETTINGS)
            }
            success
        } catch (e: Exception) {
            openSettings(Settings.ACTION_WIFI_SETTINGS)
            false
        }
    }

    fun setBluetoothState(enabled: Boolean): Boolean {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null) return false
            
            // Note: BLUETOOTH_CONNECT permission is required for Android 12+
            // And starting with Android 13+, apps cannot programmatically enable/disable Bluetooth
            // without being a system app or having special privileges. 
            // So we just open the settings for them.
            openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            false // We didn't change it programmatically, we opened settings
        } catch (e: Exception) {
            openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            false
        }
    }

    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0] // Typically the back camera
            cameraManager.setTorchMode(cameraId, enabled)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openAirplaneModeSettings() {
        openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
    }

    private fun openSettings(action: String) {
        val intent = Intent(action).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
