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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.esp32temperature.ui.theme.ESP32TemperatureTheme
import java.text.NumberFormat
import java.util.*
import kotlin.math.ceil

class MainActivity : ComponentActivity() {

    private var connectionStatus by mutableStateOf("Disconnected")
    private var deviceId by mutableStateOf("")
    
    private var dsTemp by mutableStateOf("--")
    private var bmpTemp by mutableStateOf("--")
    private var bmpAlt by mutableStateOf("--")
    private var gpsAlt by mutableStateOf("--")
    
    private var lastUpdateTime by mutableStateOf("")
    private var chartBitmap by mutableStateOf<Bitmap?>(null)
    
    private var isMetric by mutableStateOf(true)
    private var isCelsius by mutableStateOf(true)
    private var showAltOnly by mutableStateOf(false)
    private var isLocked by mutableStateOf(false)
    
    private var tempSource by mutableIntStateOf(0) // 0: DS18B20, 1: BMP280
    private var altSource by mutableIntStateOf(0)  // 0: GPS, 1: BMP280

    private var tempOffset by mutableFloatStateOf(0f)
    private var altOffset by mutableFloatStateOf(0f) // Stored in METERS

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BleForegroundService.ACTION_CONNECTION_STATUS -> {
                    connectionStatus = intent.getStringExtra(BleForegroundService.EXTRA_STATUS) ?: "Disconnected"
                    deviceId = intent.getStringExtra(BleForegroundService.EXTRA_DEVICE_ID) ?: ""
                }
                BleForegroundService.ACTION_UPDATE_DATA -> {
                    connectionStatus = intent.getStringExtra(BleForegroundService.EXTRA_STATUS) ?: connectionStatus
                    deviceId = intent.getStringExtra(BleForegroundService.EXTRA_DEVICE_ID) ?: deviceId
                    
                    dsTemp = intent.getStringExtra(BleForegroundService.EXTRA_DS_TEMP) ?: "--"
                    bmpTemp = intent.getStringExtra(BleForegroundService.EXTRA_BMP_TEMP) ?: "--"
                    bmpAlt = intent.getStringExtra(BleForegroundService.EXTRA_BMP_ALT) ?: "--"
                    gpsAlt = intent.getStringExtra(BleForegroundService.EXTRA_GPS_ALT) ?: "--"
                    
                    lastUpdateTime = intent.getStringExtra(BleForegroundService.EXTRA_LAST_UPDATE_TIME) ?: ""
                    
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
    ) { results ->
        if (results.all { it.value }) {
            startBleService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        val prefs = getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE)
        isMetric = prefs.getBoolean(BleForegroundService.KEY_IS_METRIC, true)
        isCelsius = prefs.getBoolean(BleForegroundService.KEY_IS_CELSIUS, true)
        showAltOnly = prefs.getBoolean(BleForegroundService.KEY_SHOW_ALT_ONLY, false)
        isLocked = prefs.getBoolean(BleForegroundService.KEY_IS_LOCKED, false)
        tempSource = prefs.getInt(BleForegroundService.KEY_TEMP_SOURCE, 0)
        altSource = prefs.getInt(BleForegroundService.KEY_ALT_SOURCE, 0)
        tempOffset = prefs.getFloat(BleForegroundService.KEY_TEMP_OFFSET, 0f)
        altOffset = prefs.getFloat(BleForegroundService.KEY_ALT_OFFSET, 0f)

        setContent {
            ESP32TemperatureTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        status = connectionStatus,
                        id = deviceId,
                        dsTemp = dsTemp,
                        bmpTemp = bmpTemp,
                        bmpAlt = bmpAlt,
                        gpsAlt = gpsAlt,
                        lastUpdate = lastUpdateTime,
                        bitmap = chartBitmap,
                        isMetric = isMetric,
                        isCelsius = isCelsius,
                        showAltOnly = showAltOnly,
                        isLocked = isLocked,
                        tempSource = tempSource,
                        altSource = altSource,
                        tempOffset = tempOffset,
                        altOffset = altOffset,
                        onUnitToggle = { toggleUnits() },
                        onTempToggle = { toggleTempUnits() },
                        onAltOnlyToggle = { toggleAltOnly() },
                        onLockToggle = { toggleLock() },
                        onTempSourceChange = { updateTempSource(it) },
                        onAltSourceChange = { updateAltSource(it) },
                        onTempOffsetChange = { updateTempOffset(it) },
                        onAltOffsetChange = { updateAltOffset(it) },
                        onStartClick = { startBleService() },
                        onStopClick = { stopBleService() },
                        onDmd2Click = { openDmd2() }
                    )
                }
            }
        }
    }

    private fun updateTempSource(index: Int) {
        tempSource = index
        getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE).edit {
            putInt(BleForegroundService.KEY_TEMP_SOURCE, index)
        }
    }

    private fun updateAltSource(index: Int) {
        altSource = index
        getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE).edit {
            putInt(BleForegroundService.KEY_ALT_SOURCE, index)
        }
    }

    private fun updateTempOffset(offset: Float) {
        tempOffset = offset
        getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE).edit {
            putFloat(BleForegroundService.KEY_TEMP_OFFSET, offset)
        }
    }

    private fun updateAltOffset(offsetMeters: Float) {
        altOffset = offsetMeters
        getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE).edit {
            putFloat(BleForegroundService.KEY_ALT_OFFSET, offsetMeters)
        }
    }

    private fun toggleUnits() {
        isMetric = !isMetric
        getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(BleForegroundService.KEY_IS_METRIC, isMetric)
        }
    }

    private fun toggleTempUnits() {
        isCelsius = !isCelsius
        getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(BleForegroundService.KEY_IS_CELSIUS, isCelsius)
        }
    }

    private fun toggleAltOnly() {
        showAltOnly = !showAltOnly
        getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(BleForegroundService.KEY_SHOW_ALT_ONLY, showAltOnly)
        }
    }

    private fun toggleLock() {
        if (!isLocked && connectionStatus != "Connected") {
            Toast.makeText(this, "Connect to a sensor first to lock it", Toast.LENGTH_SHORT).show()
            return
        }

        isLocked = !isLocked
        getSharedPreferences(BleForegroundService.PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(BleForegroundService.KEY_IS_LOCKED, isLocked)
            if (isLocked) {
                putString(BleForegroundService.KEY_LOCKED_DEVICE_ID, deviceId)
                Toast.makeText(this@MainActivity, "Sensor Locked to: $deviceId", Toast.LENGTH_SHORT).show()
            } else {
                putString(BleForegroundService.KEY_LOCKED_DEVICE_ID, "")
                Toast.makeText(this@MainActivity, "Sensor Unlocked", Toast.LENGTH_SHORT).show()
            }
        }
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
        ContextCompat.registerReceiver(this, dataReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
            @Suppress("DEPRECATION")
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
        } else {
            startBleService()
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
        dsTemp = "--"
        bmpTemp = "--"
        bmpAlt = "--"
        gpsAlt = "--"
        lastUpdateTime = ""
        chartBitmap = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    status: String, 
    id: String, 
    dsTemp: String,
    bmpTemp: String,
    bmpAlt: String,
    gpsAlt: String,
    lastUpdate: String,
    bitmap: Bitmap?,
    isMetric: Boolean,
    isCelsius: Boolean,
    showAltOnly: Boolean,
    isLocked: Boolean,
    tempSource: Int,
    altSource: Int,
    tempOffset: Float,
    altOffset: Float,
    onUnitToggle: () -> Unit,
    onTempToggle: () -> Unit,
    onAltOnlyToggle: () -> Unit,
    onLockToggle: () -> Unit,
    onTempSourceChange: (Int) -> Unit,
    onAltSourceChange: (Int) -> Unit,
    onTempOffsetChange: (Float) -> Unit,
    onAltOffsetChange: (Float) -> Unit,
    onStartClick: () -> Unit, 
    onStopClick: () -> Unit,
    onDmd2Click: () -> Unit
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // Scrollbar visibility logic - fades out when not scrolling
    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (scrollState.isScrollInProgress) 0.6f else 0f,
        animationSpec = tween(durationMillis = if (scrollState.isScrollInProgress) 100 else 800),
        label = "scrollbarAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
            .drawWithContent {
                drawContent()
                if (scrollState.maxValue > 0 && scrollbarAlpha > 0f) {
                    val scrollbarWidth = 3.dp.toPx() // Smaller scrollbar
                    val visibleHeight = size.height
                    val totalHeight = visibleHeight + scrollState.maxValue
                    val scrollbarHeight = visibleHeight * (visibleHeight / totalHeight)
                    val scrollbarYOffset = (scrollState.value.toFloat() / scrollState.maxValue) * (visibleHeight - scrollbarHeight)
                    
                    drawRoundRect(
                        color = ComposeColor.White.copy(alpha = scrollbarAlpha),
                        topLeft = Offset(size.width - scrollbarWidth, scrollbarYOffset),
                        size = Size(scrollbarWidth, scrollbarHeight),
                        cornerRadius = CornerRadius(scrollbarWidth / 2, scrollbarWidth / 2)
                    )
                }
            },
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
                text = "Device Address: $id",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Source Selection Dropdowns
        val tempOptions = listOf("DS18B20", "BMP280")
        var tempExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = tempExpanded,
            onExpandedChange = { tempExpanded = !tempExpanded }
        ) {
            OutlinedTextField(
                value = tempOptions[tempSource],
                onValueChange = {},
                readOnly = true,
                label = { Text("Temperature Source") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tempExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = tempExpanded,
                onDismissRequest = { tempExpanded = false }
            ) {
                tempOptions.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onTempSourceChange(index)
                            tempExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val altOptions = listOf("Internal GPS", "BMP280")
        var altExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = altExpanded,
            onExpandedChange = { altExpanded = !altExpanded }
        ) {
            OutlinedTextField(
                value = altOptions[altSource],
                onValueChange = {},
                readOnly = true,
                label = { Text("Altitude Source") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = altExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = altExpanded,
                onDismissRequest = { altExpanded = false }
            ) {
                altOptions.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onAltSourceChange(index)
                            altExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Offset Boxes Aligned Horizontally
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Dynamic Temperature Offset Title and Unit Conversion
            val tempLabel = if (isCelsius) "Temp Offset (°C)" else "Temp Offset (°F)"
            val displayedTempOffset = if (isCelsius) tempOffset else tempOffset * 9/5
            
            var tempOffText by remember(isCelsius, tempOffset) { 
                mutableStateOf(String.format(Locale.US, "%.1f", displayedTempOffset)) 
            }
            
            OutlinedTextField(
                value = tempOffText,
                onValueChange = {
                    tempOffText = it
                    it.toFloatOrNull()?.let { f -> 
                        val celsiusOffsetValue = if (isCelsius) f else f * 5/9
                        onTempOffsetChange(celsiusOffsetValue) 
                    }
                },
                label = { Text(tempLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )

            // Dynamic Altitude Offset Title and Unit Conversion
            val altLabel = if (isMetric) "Alt Offset (meters)" else "Alt Offset (ft)"
            val displayedAltOffset = if (isMetric) altOffset else altOffset * 3.28084f
            
            var altOffText by remember(isMetric, altOffset) { 
                mutableStateOf(String.format(Locale.US, "%.1f", displayedAltOffset)) 
            }
            
            OutlinedTextField(
                value = altOffText,
                onValueChange = {
                    altOffText = it
                    it.toFloatOrNull()?.let { f -> 
                        val metersValue = if (isMetric) f else f / 3.28084f
                        onAltOffsetChange(metersValue) 
                    }
                },
                label = { Text(altLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        val isDsFault = dsTemp.toFloatOrNull() == -127.0f
        val isBmpFault = bmpTemp.contains("nan", ignoreCase = true) || bmpAlt.contains("nan", ignoreCase = true)

        val currentTemp = if (tempSource == 0) dsTemp else bmpTemp
        val displayTemp = if (currentTemp == "--") "--" 
                          else if (tempSource == 0 && isDsFault) "" 
                          else if (tempSource == 1 && isBmpFault) ""
                          else {
            val t = currentTemp.toFloatOrNull() ?: 0f
            if (isCelsius) String.format(Locale.US, "%.1f °C", t) else String.format(Locale.US, "%.1f °F", t * 9/5 + 32)
        }
        
        val currentAlt = if (altSource == 0) gpsAlt else bmpAlt
        val displayAlt = if (currentAlt == "--") "--" 
                          else if (altSource == 1 && isBmpFault) ""
                          else {
            val a = currentAlt.toFloatOrNull() ?: 0f
            val value = if (isMetric) a else a * 3.28084f
            val roundedValue = ceil(value).toInt()
            val formatter = NumberFormat.getIntegerInstance(Locale.getDefault())
            val formatted = formatter.format(roundedValue)
            if (isMetric) "$formatted m" else "$formatted ft"
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Temperature: $displayTemp", style = MaterialTheme.typography.titleLarge)
                if (lastUpdate.isNotEmpty()) {
                    Text(
                        text = " ($lastUpdate)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    
                    val errorMsg = when {
                        isDsFault && isBmpFault -> "Both DS18B20 and BMP280 sensors are not connected or sensor fault"
                        isDsFault && tempSource == 0 -> "DS18B20 is not connected or sensor fault"
                        isBmpFault && (tempSource == 1 || altSource == 1) -> "BMP280 is not connected or sensor fault"
                        else -> null
                    }
                    
                    if (errorMsg != null) {
                        Text(
                            text = " $errorMsg",
                            color = ComposeColor.Red,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
            Text(text = "Altitude: $displayAlt", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(onClick = onStartClick, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Default.Refresh, "Refresh", modifier = Modifier.size(40.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            FilledIconButton(onClick = onStopClick, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = ComposeColor.Red)) {
                Icon(Icons.Default.Stop, "Stop", modifier = Modifier.size(40.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            FilledIconButton(onClick = onDmd2Click, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Default.Speed, "DMD2", modifier = Modifier.size(40.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            FilledIconButton(onClick = { showInfoDialog = true }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Default.Info, "Info", modifier = Modifier.size(40.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth().height(250.dp).padding(8.dp)) {
            bitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = "Sensor Chart", modifier = Modifier.fillMaxSize())
            } ?: Text("Waiting for data...", modifier = Modifier.align(Alignment.Center))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth()
        ) {
            UnitToggle(label = if (isMetric) "Meters" else "Feet", checked = !isMetric, onCheckedChange = { onUnitToggle() })
            UnitToggle(label = if (isCelsius) "°C" else "°F", checked = !isCelsius, onCheckedChange = { onTempToggle() })
            UnitToggle(label = "Alt Only", checked = showAltOnly, onCheckedChange = { onAltOnlyToggle() })
            UnitToggle(label = "Lock", checked = isLocked, onCheckedChange = { onLockToggle() })
        }
    }

    if (showInfoDialog) {
        Dialog(onDismissRequest = { showInfoDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(ComposeColor.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                IconButton(onClick = { showInfoDialog = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Default.Close, "Close", tint = ComposeColor.White)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Add an External Widget screen on DMD2 home screen and then select this widget to show the chart and data from this app.",
                        color = ComposeColor.White, textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Text(text = "Made With Pride by ", color = ComposeColor.White, fontWeight = FontWeight.Bold)
                        Text(text = "Pratik Kumar", color = ComposeColor(0xFF87CEEB), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.youtube.com/@sleepyvoyager".toUri()))
                        }) {
                            Icon(
                                imageVector = Icons.Default.PlayCircle,
                                contentDescription = "YouTube",
                                tint = ComposeColor.Red,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        IconButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.instagram.com/sleepyvoyager/".toUri()))
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_instagram),
                                contentDescription = "Instagram",
                                tint = ComposeColor.Unspecified,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        IconButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/pratik1989/ESP32_Temperature".toUri()))
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = "GitHub",
                                tint = ComposeColor.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnitToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.customScale(0.7f))
    }
}

private fun Modifier.customScale(scale: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
            placeable.placeRelative(0, 0)
        }
    }
)
