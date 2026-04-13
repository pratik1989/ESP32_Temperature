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
import androidx.compose.material3.*
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
    private var isMetric by mutableStateOf(true)

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BleForegroundService.ACTION_CONNECTION_STATUS -> {
                    connectionStatus = intent.getStringExtra(BleForegroundService.EXTRA_STATUS) ?: "Disconnected"
                    deviceId = intent.getStringExtra(BleForegroundService.EXTRA_DEVICE_ID) ?: ""
                }
                BleForegroundService.ACTION_UPDATE_DATA -> {
                    val newStatus = intent.getStringExtra(BleForegroundService.EXTRA_STATUS)
                    if (newStatus != null) {
                        connectionStatus = newStatus
                    }
                    val newId = intent.getStringExtra(BleForegroundService.EXTRA_DEVICE_ID)
                    if (newId != null) {
                        deviceId = newId
                    }
                    
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

        val prefs = getSharedPreferences(BleForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
        isMetric = prefs.getBoolean(BleForegroundService.KEY_IS_METRIC, true)

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
                        isMetric = isMetric,
                        onUnitToggle = { toggleUnits() },
                        onStartClick = { startBleService() },
                        onStopClick = { stopBleService() }
                    )
                }
            }
        }
    }

    private fun toggleUnits() {
        isMetric = !isMetric
        getSharedPreferences(BleForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BleForegroundService.KEY_IS_METRIC, isMetric)
            .apply()
        
        broadcastUpdate()
    }

    private fun broadcastUpdate() {
        val intent = Intent(BleForegroundService.ACTION_UPDATE_DATA)
        intent.putExtra(BleForegroundService.EXTRA_TEMPERATURE, temperature)
        intent.putExtra(BleForegroundService.EXTRA_ALTITUDE, altitude)
        intent.putExtra(BleForegroundService.EXTRA_STATUS, connectionStatus)
        intent.putExtra(BleForegroundService.EXTRA_DEVICE_ID, deviceId)
        intent.setPackage(packageName)
        sendBroadcast(intent)
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
        
        // Update widget when stopping service
        val updateIntent = Intent(BleForegroundService.ACTION_CONNECTION_STATUS)
        updateIntent.putExtra(BleForegroundService.EXTRA_STATUS, "Disconnected")
        updateIntent.putExtra(BleForegroundService.EXTRA_TEMPERATURE, "--")
        updateIntent.putExtra(BleForegroundService.EXTRA_ALTITUDE, "--")
        updateIntent.setPackage(packageName)
        sendBroadcast(updateIntent)
    }
}

@Composable
fun MainScreen(
    status: String, 
    id: String, 
    temp: String, 
    alt: String, 
    bitmap: Bitmap?,
    isMetric: Boolean,
    onUnitToggle: () -> Unit,
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
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        
        if (id.isNotEmpty()) {
            Text(
                text = "Device ID: $id",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        val displayTemp = if (temp == "--") "--" else {
            val t = temp.toFloatOrNull() ?: 0f
            String.format("%.1f °C", t)
        }
        
        val displayAlt = if (alt == "--") "--" else {
            val a = alt.toFloatOrNull() ?: 0f
            if (isMetric) String.format("%.1f m", a) else String.format("%.1f ft", a * 3.28084f)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.Start) {
            Text(text = "Temperature: $displayTemp", style = MaterialTheme.typography.titleMedium)
            Text(text = "Altitude: $displayAlt", style = MaterialTheme.typography.titleMedium)
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

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (isMetric) "Altitude: Meters" else "Altitude: Feet", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = !isMetric, 
                onCheckedChange = { onUnitToggle() }, 
                modifier = Modifier.padding(start = 12.dp)
            )
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
