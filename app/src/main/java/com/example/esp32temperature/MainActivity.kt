package com.example.esp32temperature

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.esp32temperature.ui.theme.ESP32TemperatureTheme

class MainActivity : ComponentActivity() {

    private var connectionStatus by mutableStateOf("Disconnected")
    private var deviceId by mutableStateOf("")
    private var temperature by mutableStateOf("--")
    private var altitude by mutableStateOf("--")
    private var chartBitmap by mutableStateOf<Bitmap?>(null)

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BleForegroundService.ACTION_CONNECTION_STATUS -> {
                    connectionStatus = intent.getStringExtra(BleForegroundService.EXTRA_STATUS) ?: "Disconnected"
                    deviceId = intent.getStringExtra(BleForegroundService.EXTRA_DEVICE_ID) ?: ""
                }
                BleForegroundService.ACTION_UPDATE_DATA -> {
                    temperature = intent.getStringExtra(BleForegroundService.EXTRA_TEMPERATURE) ?: "--"
                    altitude = intent.getStringExtra(BleForegroundService.EXTRA_ALTITUDE) ?: "--"
                    
                    val byteArray = intent.getByteArrayExtra(BleForegroundService.EXTRA_CHART_BYTES)
                    if (byteArray != null) {
                        chartBitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    }
                }
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
                        temp = temperature,
                        alt = altitude,
                        bitmap = chartBitmap,
                        onStartClick = { startBleService() },
                        onStopClick = { stopBleService() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(BleForegroundService.ACTION_CONNECTION_STATUS)
            addAction(BleForegroundService.ACTION_UPDATE_DATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dataReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
        temperature = "--"
        altitude = "--"
        chartBitmap = null
    }
}

@Composable
fun MainScreen(
    status: String, 
    id: String, 
    temp: String, 
    alt: String, 
    bitmap: Bitmap?,
    onStartClick: () -> Unit, 
    onStopClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Top,
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
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.Start) {
            Text(text = "Temperature: $temp °C", style = MaterialTheme.typography.titleMedium)
            Text(text = "Altitude: $alt ft", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .padding(8.dp)
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Sensor Chart",
                    modifier = Modifier.fillMaxSize()
                )
            } ?: Text("Waiting for data...", modifier = Modifier.align(Alignment.Center))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartClick,
            modifier = Modifier.padding(8.dp).fillMaxWidth(0.8f)
        ) {
            Text("Start Sensor Connection")
        }
        
        Button(
            onClick = onStopClick,
            modifier = Modifier.padding(8.dp).fillMaxWidth(0.8f)
        ) {
            Text("Stop Connection")
        }
    }
}
