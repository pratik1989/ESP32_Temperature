package com.example.esp32temperature

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.esp32temperature.ui.theme.ESP32TemperatureTheme

class MainActivity : ComponentActivity() {

    private var connectionStatus by mutableStateOf("Disconnected")
    private var deviceId by mutableStateOf("")

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BleForegroundService.ACTION_CONNECTION_STATUS) {
                connectionStatus = intent.getStringExtra(BleForegroundService.EXTRA_STATUS) ?: "Disconnected"
                deviceId = intent.getStringExtra(BleForegroundService.EXTRA_DEVICE_ID) ?: ""
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            ESP32TemperatureTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        status = connectionStatus,
                        id = deviceId,
                        onStartClick = { startBleService() },
                        onStopClick = { stopBleService() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(BleForegroundService.ACTION_CONNECTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startBleService() {
        val intent = Intent(this, BleForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBleService() {
        val intent = Intent(this, BleForegroundService::class.java)
        stopService(intent)
        connectionStatus = "Disconnected"
        deviceId = ""
    }
}

@Composable
fun MainScreen(status: String, id: String, onStartClick: () -> Unit, onStopClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val statusColor = if (status == "Connected") ComposeColor(0xFF4CAF50) else ComposeColor.Red
        
        Text(
            text = status,
            color = statusColor,
            style = MaterialTheme.typography.headlineMedium
        )
        
        if (id.isNotEmpty()) {
            Text(
                text = "Device ID: $id",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartClick,
            modifier = Modifier.padding(8.dp).fillMaxWidth(0.7f)
        ) {
            Text("Start Sensor Connection")
        }
        
        Button(
            onClick = onStopClick,
            modifier = Modifier.padding(8.dp).fillMaxWidth(0.7f)
        ) {
            Text("Stop Connection")
        }
    }
}
