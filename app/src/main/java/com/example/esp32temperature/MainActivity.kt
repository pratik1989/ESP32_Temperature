package com.example.esp32temperature

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.esp32temperature.ui.theme.ESP32TemperatureTheme
import java.text.NumberFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var connectionStatus by mutableStateOf("Disconnected")
    private var deviceId by mutableStateOf("")
    private var temperature by mutableStateOf("--")
    private var altitude by mutableStateOf("--")
    private var chartBitmap by mutableStateOf<Bitmap?>(null)
    private var isMetric by mutableStateOf(true)
    private var isLocked by mutableStateOf(false)

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
        isLocked = prefs.getBoolean(BleForegroundService.KEY_IS_LOCKED, false)

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
                        isLocked = isLocked,
                        onUnitToggle = { toggleUnits() },
                        onLockToggle = { toggleLock() },
                        onStartClick = { startBleService() },
                        onStopClick = { stopBleService() },
                        onDmd2Click = { openDmd2() }
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

    private fun toggleLock() {
        if (!isLocked && connectionStatus != "Connected") {
            Toast.makeText(this, "Connect to a sensor first to lock it", Toast.LENGTH_SHORT).show()
            return
        }

        isLocked = !isLocked
        val prefs = getSharedPreferences(BleForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putBoolean(BleForegroundService.KEY_IS_LOCKED, isLocked)
        
        if (isLocked) {
            editor.putString(BleForegroundService.KEY_LOCKED_DEVICE_ID, deviceId)
            Toast.makeText(this, "Sensor Locked to: $deviceId", Toast.LENGTH_SHORT).show()
        } else {
            editor.putString(BleForegroundService.KEY_LOCKED_DEVICE_ID, "")
            Toast.makeText(this, "Sensor Unlocked", Toast.LENGTH_SHORT).show()
        }
        editor.apply()
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

    private fun openDmd2() {
        val packageName = "com.thorkracing.dmd2launcher"
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "DMD2 app not found", Toast.LENGTH_SHORT).show()
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
    isLocked: Boolean,
    onUnitToggle: () -> Unit,
    onLockToggle: () -> Unit,
    onStartClick: () -> Unit, 
    onStopClick: () -> Unit,
    onDmd2Click: () -> Unit
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
            val value = if (isMetric) a else a * 3.28084f
            val formatter = NumberFormat.getInstance(Locale("en", "IN")).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
            val formatted = formatter.format(value)
            if (isMetric) "$formatted m" else "$formatted ft"
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.Start) {
            Text(text = "Temperature: $displayTemp", style = MaterialTheme.typography.titleMedium)
            Text(text = "Altitude: $displayAlt", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(8.dp))

        IconButton(
            onClick = { showInfoDialog = true },
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

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
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = if (isMetric) "Meters" else "Feet", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = !isMetric, 
                    onCheckedChange = { onUnitToggle() }, 
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Lock Sensor", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = isLocked, 
                    onCheckedChange = { onLockToggle() }, 
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartClick,
            modifier = Modifier.padding(4.dp).fillMaxWidth(0.8f)
        ) {
            Text("Start Sensor Connection")
        }
        
        Button(
            onClick = onStopClick,
            modifier = Modifier.padding(4.dp).fillMaxWidth(0.8f)
        ) {
            Text("Stop Connection")
        }

        Spacer(modifier = Modifier.height(16.dp))

        IconButton(
            onClick = onDmd2Click,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = "Open DMD2",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showInfoDialog) {
        Dialog(onDismissRequest = { showInfoDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(
                        color = ComposeColor.Black.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
            ) {
                IconButton(
                    onClick = { showInfoDialog = false },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = ComposeColor.White
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Made With Pride for Riders By",
                        color = ComposeColor.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pratik Kumar",
                        color = ComposeColor(0xFF87CEEB), // Sky Blue
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = android.R.drawable.ic_menu_slideshow), // Temporary placeholder
                            contentDescription = "YouTube",
                            modifier = Modifier
                                .size(40.dp)
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/@sleepyvoyager"))
                                    context.startActivity(intent)
                                }
                        )
                        Spacer(modifier = Modifier.width(32.dp))
                        Image(
                            painter = painterResource(id = android.R.drawable.ic_menu_camera), // Temporary placeholder
                            contentDescription = "Instagram",
                            modifier = Modifier
                                .size(40.dp)
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/sleepyvoyager"))
                                    context.startActivity(intent)
                                }
                        )
                    }
                }
            }
        }
    }
}
