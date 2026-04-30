package com.example.esp32temperature

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

@SuppressLint("MissingPermission")
class BleForegroundService : Service() {

    private val TAG = "BleForegroundService"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var locationManager: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var shouldAutoReconnect = true

    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var dsTemp = "--"
    private var bmpTemp = "--"
    private var bmpAlt = "--"
    private var gpsAlt = "--"
    private var currentStatus = "Disconnected"
    private var connectedDeviceId = ""
    private var lastUpdateTime = ""

    private var connectionStartTime: Long = 0
    
    data class ChartPoint(val timestamp: Long, val dsTemp: Float?, val bmpTemp: Float?, val bmpAlt: Float?, val gpsAlt: Float?)
    private val dataHistory = mutableListOf<ChartPoint>()
    private val HISTORY_LIMIT_MS = 3600000L // 1 hour

    companion object {
        const val CHANNEL_ID = "BleForegroundServiceChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_UPDATE_DATA = "com.example.esp32temperature.UPDATE_DATA"
        const val ACTION_CONNECTION_STATUS = "com.example.esp32temperature.CONNECTION_STATUS"
        const val ACTION_REFRESH = "com.example.esp32temperature.REFRESH"

        const val EXTRA_DS_TEMP = "EXTRA_DS_TEMP"
        const val EXTRA_BMP_TEMP = "EXTRA_BMP_TEMP"
        const val EXTRA_BMP_ALT = "EXTRA_BMP_ALT"
        const val EXTRA_GPS_ALT = "EXTRA_GPS_ALT"
        
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"
        const val EXTRA_CHART_BYTES = "EXTRA_CHART_BYTES"
        const val EXTRA_LAST_UPDATE_TIME = "EXTRA_LAST_UPDATE_TIME"
        
        const val PREFS_NAME = "ESP32Prefs"
        const val KEY_IS_METRIC = "is_metric"
        const val KEY_IS_CELSIUS = "is_celsius"
        const val KEY_SHOW_ALT_ONLY = "show_alt_only"
        const val KEY_LOCKED_DEVICE_ID = "locked_device_id"
        const val KEY_IS_LOCKED = "is_locked"
        
        const val KEY_TEMP_SOURCE = "temp_source" // 0: DS18B20, 1: BMP280
        const val KEY_ALT_SOURCE = "alt_source"   // 0: GPS, 1: BMP280

        const val KEY_TEMP_OFFSET = "temp_offset"
        const val KEY_ALT_OFFSET = "alt_offset"
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (shouldAutoReconnect && bluetoothGatt == null && !isScanning) {
                Log.d(TAG, "Watchdog: checking connection status...")
                checkAndConnectIfAlreadyPaired()
                if (bluetoothGatt == null && !isScanning) {
                    startScanning()
                }
            }
            handler.postDelayed(this, 15000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        handler.post(watchdogRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            updateWidgetAndBroadcast()
            return START_STICKY
        }

        shouldAutoReconnect = true
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DMD Temperature Monitor")
            .setContentText("Monitoring Sensors & GPS...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        handler.post { 
            if (!checkAndConnectIfAlreadyPaired()) {
                startScanning() 
            }
        }
        handler.post { startLocationUpdates() }
        
        // Push initial blank chart
        handler.post { updateWidgetAndBroadcast() }
        
        return START_STICKY
    }

    private fun checkAndConnectIfAlreadyPaired(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean(KEY_IS_LOCKED, false)
        val lockedId = prefs.getString(KEY_LOCKED_DEVICE_ID, "")

        // Check bonded (paired) devices
        val bondedDevices = adapter.bondedDevices
        for (device in bondedDevices) {
            val deviceName = device.name ?: ""
            if (isTargetDevice(deviceName, device.address, isLocked, lockedId)) {
                Log.d(TAG, "Found paired target device: $deviceName (${device.address}). Connecting...")
                connectToDevice(device)
                return true
            }
        }
        
        // Also check currently connected devices
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        for (device in connectedDevices) {
            val deviceName = device.name ?: ""
            if (isTargetDevice(deviceName, device.address, isLocked, lockedId)) {
                Log.d(TAG, "Device already connected via GATT: $deviceName (${device.address})")
                if (bluetoothGatt == null) {
                    connectToDevice(device)
                }
                return true
            }
        }

        return false
    }

    private fun isTargetDevice(name: String, address: String, isLocked: Boolean, lockedId: String?): Boolean {
        if (isLocked && !lockedId.isNullOrEmpty()) {
            return address == lockedId
        }
        return name.contains("ESP32_Te", ignoreCase = true) || 
               name.contains("Temperature", ignoreCase = true) ||
               name.contains("MotoControl", ignoreCase = true)
    }

    private fun startLocationUpdates() {
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, locationListener)
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing")
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasAltitude()) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val aOffset = prefs.getFloat(KEY_ALT_OFFSET, 0f)
                val calibratedAlt = location.altitude + (aOffset / 3.28084f)
                gpsAlt = String.format("%.1f", calibratedAlt)
                recordData()
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startScanning() {
        if (isScanning || bluetoothGatt != null) return
        
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            broadcastStatus("Bluetooth Disabled", "")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            broadcastStatus("Scanner Error", "")
            return
        }

        isScanning = true
        broadcastStatus("Scanning", "")
        Log.d(TAG, "Starting BLE Scan...")
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}")
            isScanning = false
            broadcastStatus("Scan Error", "")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val scanRecord = result.scanRecord
            val deviceName = scanRecord?.deviceName ?: device.name ?: "Unknown"
            
            Log.v(TAG, "Discovered: $deviceName [${device.address}] RSSI: ${result.rssi}")

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isLocked = prefs.getBoolean(KEY_IS_LOCKED, false)
            val lockedId = prefs.getString(KEY_LOCKED_DEVICE_ID, "")

            if (isTargetDevice(deviceName, device.address, isLocked, lockedId)) {
                Log.d(TAG, "Target device found: $deviceName (${device.address}). Stopping scan and connecting...")
                stopScanning()
                connectToDevice(device)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning = false
            broadcastStatus("Scan Failed ($errorCode)", "")
        }
    }

    private fun stopScanning() {
        if (isScanning) {
            Log.d(TAG, "Stopping BLE Scan")
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {}
            isScanning = false
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (bluetoothGatt != null) {
            Log.d(TAG, "Closing existing GATT connection before new one")
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        broadcastStatus("Connecting", device.address)
        Log.d(TAG, "Connecting to GATT on ${device.address}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT Connected to ${gatt.device.address}")
                broadcastStatus("Connected", gatt.device.address)
                connectionStartTime = System.currentTimeMillis()
                handler.postDelayed({ 
                    Log.d(TAG, "Requesting MTU...")
                    gatt.requestMtu(128) 
                }, 1000)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT DisCONNECTED from ${gatt.device.address}")
                if (gatt == bluetoothGatt) {
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
                broadcastStatus("Disconnected", "")
                dsTemp = "--"
                bmpTemp = "--"
                bmpAlt = "--"
                lastUpdateTime = ""
                updateWidgetAndBroadcast()
                if (shouldAutoReconnect) {
                    handler.postDelayed({ 
                        if (bluetoothGatt == null) startScanning() 
                    }, 5000)
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT Error: status=$status")
                gatt.close()
                if (gatt == bluetoothGatt) bluetoothGatt = null
                broadcastStatus("Error $status", "")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu, status=$status")
            handler.postDelayed({ 
                Log.d(TAG, "Discovering services...")
                gatt.discoverServices() 
            }, 1000)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "Target service NOT found: $SERVICE_UUID")
                    return
                }
                
                val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    Log.d(TAG, "Target characteristic found. Enabling notifications...")
                    handler.postDelayed({
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        } else {
                            Log.e(TAG, "Notification descriptor not found!")
                        }
                    }, 500)
                } else {
                    Log.e(TAG, "Target characteristic NOT found: $CHARACTERISTIC_UUID")
                }
            } else {
                Log.e(TAG, "onServicesDiscovered failed with status: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notifications enabled successfully")
            } else {
                Log.e(TAG, "Failed to enable notifications, status=$status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val data = characteristic.value?.let { String(it) } ?: ""
                Log.v(TAG, "Data received: $data")
                
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val tOffset = prefs.getFloat(KEY_TEMP_OFFSET, 0f)
                val aOffset = prefs.getFloat(KEY_ALT_OFFSET, 0f)
                
                if (data.contains(",")) {
                    val parts = data.split(",")
                    if (parts.size >= 3) {
                        val rawDs = parts[0].toFloatOrNull()
                        dsTemp = if (rawDs != null && rawDs != -127f) (rawDs + tOffset).toString() else parts[0]
                        
                        val rawBmpT = parts[1].toFloatOrNull()
                        bmpTemp = if (rawBmpT != null) (rawBmpT + tOffset).toString() else parts[1]
                        
                        val altInFeet = parts[2].toFloatOrNull()
                        bmpAlt = if (altInFeet != null) {
                            String.format("%.1f", (altInFeet + aOffset) / 3.28084f)
                        } else {
                            parts[2] // Keep "nan" or other string
                        }
                    }
                } else {
                    val rawDs = data.toFloatOrNull()
                    dsTemp = if (rawDs != null && rawDs != -127f) (rawDs + tOffset).toString() else data
                }
                lastUpdateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                recordData()
            }
        }
    }

    private fun recordData() {
        val now = System.currentTimeMillis()
        val dsT = dsTemp.toFloatOrNull()
        val bmpT = bmpTemp.toFloatOrNull()
        val bmpA = bmpAlt.toFloatOrNull()
        val gpsA = gpsAlt.toFloatOrNull()
        
        dataHistory.add(ChartPoint(now, dsT, bmpT, bmpA, gpsA))
        dataHistory.removeAll { now - it.timestamp > HISTORY_LIMIT_MS }
        
        updateWidgetAndBroadcast()
    }

    private fun updateWidgetAndBroadcast() {
        val chartBitmap = drawChart()
        val stream = java.io.ByteArrayOutputStream()
        chartBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val chartBytes = stream.toByteArray()

        val intent = Intent(ACTION_UPDATE_DATA)
        intent.putExtra(EXTRA_DS_TEMP, dsTemp)
        intent.putExtra(EXTRA_BMP_TEMP, bmpTemp)
        intent.putExtra(EXTRA_BMP_ALT, bmpAlt)
        intent.putExtra(EXTRA_GPS_ALT, gpsAlt)
        intent.putExtra(EXTRA_STATUS, currentStatus)
        intent.putExtra(EXTRA_DEVICE_ID, connectedDeviceId)
        intent.putExtra(EXTRA_LAST_UPDATE_TIME, lastUpdateTime)
        if (chartBytes.isNotEmpty()) intent.putExtra(EXTRA_CHART_BYTES, chartBytes)
        
        intent.setPackage(packageName)
        sendBroadcast(intent)

        val widgetIntent = Intent(this, TemperatureWidgetProvider::class.java)
        widgetIntent.action = ACTION_UPDATE_DATA
        widgetIntent.putExtras(intent)
        sendBroadcast(widgetIntent)
    }

    private fun broadcastStatus(status: String, deviceId: String) {
        currentStatus = status
        connectedDeviceId = deviceId
        
        val intent = Intent(ACTION_CONNECTION_STATUS)
        intent.putExtra(EXTRA_STATUS, status)
        intent.putExtra(EXTRA_DEVICE_ID, deviceId)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        
        val widgetIntent = Intent(this, TemperatureWidgetProvider::class.java)
        widgetIntent.action = ACTION_CONNECTION_STATUS
        widgetIntent.putExtra(EXTRA_STATUS, status)
        widgetIntent.putExtra(EXTRA_DEVICE_ID, deviceId)
        sendBroadcast(widgetIntent)
        
        // Also update the chart to show current status (e.g. blank chart when disconnected)
        updateWidgetAndBroadcast()
    }

    private fun drawChart(): Bitmap {
        val width = 600
        val height = 250
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val now = System.currentTimeMillis()
        val chartStartTime = if (dataHistory.isNotEmpty()) Math.max(now - HISTORY_LIMIT_MS, dataHistory.first().timestamp) else now - HISTORY_LIMIT_MS
        val totalRange = Math.max(10000L, now - chartStartTime)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tempSource = prefs.getInt(KEY_TEMP_SOURCE, 0)
        val altSource = prefs.getInt(KEY_ALT_SOURCE, 0)
        val isMetric = prefs.getBoolean(KEY_IS_METRIC, true)
        val isCelsius = prefs.getBoolean(KEY_IS_CELSIUS, true)
        val showAltOnly = prefs.getBoolean(KEY_SHOW_ALT_ONLY, false)

        // Filter valid points
        val tempPoints = dataHistory.filter { it.timestamp >= chartStartTime }.map { 
            val tValue = if (tempSource == 0) it.dsTemp else it.bmpTemp
            val isFault = if (tempSource == 0) (tValue == -127.0f) else (tValue == null)
            it.timestamp to if (isFault) null else tValue
        }.filter { it.second != null }
        
        val altPoints = dataHistory.filter { it.timestamp >= chartStartTime }.map { 
            val aValue = if (altSource == 0) it.gpsAlt else it.bmpAlt 
            val isFault = (aValue == null)
            it.timestamp to if (isFault) null else aValue
        }.filter { it.second != null }

        val padding = 5f
        val middleY = height / 2f
        
        // Sections
        val topChartTop = if (showAltOnly) 0f else padding
        val topChartBottom = if (showAltOnly) 0f else middleY - 5f
        val topChartHeight = topChartBottom - topChartTop

        val bottomChartTop = if (showAltOnly) padding else middleY + 5f
        val bottomChartBottom = height - padding
        val bottomChartHeight = bottomChartBottom - bottomChartTop

        fun getX(ts: Long) = ((ts - chartStartTime).toFloat() / totalRange) * width
        
        val paint = Paint().apply {
            strokeWidth = 8f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        if (!showAltOnly) {
            // Draw middle separator (White as requested)
            paint.color = Color.WHITE
            paint.alpha = 100
            paint.strokeWidth = 1f
            canvas.drawLine(0f, middleY, width.toFloat(), middleY, paint)

            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 12f
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }

            if (tempPoints.size >= 2) {
                val minT = tempPoints.minOf { it.second!! }
                val maxT = tempPoints.maxOf { it.second!! }

                val bufferT = ((maxT - minT) * 0.3f).coerceAtLeast(2f)
                val drawMinT = minT - bufferT
                val drawMaxT = maxT + bufferT
                val rangeT = (drawMaxT - drawMinT)

                paint.color = Color.MAGENTA
                paint.strokeWidth = 8f
                val path = Path()
                fun getY(t: Float) = topChartBottom - ((t - drawMinT) / rangeT) * topChartHeight

                path.moveTo(getX(tempPoints[0].first), getY(tempPoints[0].second!!))
                for (i in 1 until tempPoints.size) {
                    val x1 = getX(tempPoints[i - 1].first)
                    val y1 = getY(tempPoints[i - 1].second!!)
                    val x2 = getX(tempPoints[i].first)
                    val y2 = getY(tempPoints[i].second!!)
                    path.cubicTo((x1 + x2) / 2, y1, (x1 + x2) / 2, y2, x2, y2)
                }
                canvas.drawPath(path, paint)

                val formatTemp = { t: Float ->
                    val displayT = if (isCelsius) t else (t * 9/5 + 32)
                    val unit = if (isCelsius) "C" else "F"
                    "${ceil(displayT.toDouble()).toInt()}$unit"
                }
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(formatTemp(maxT), 5f, topChartTop + 12f, textPaint)
                canvas.drawText(formatTemp(minT), 5f, topChartBottom - 2f, textPaint)
            } else {
                // Placeholder legends for Temperature
                val unit = if (isCelsius) "°C" else "°F"
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("--$unit", 5f, topChartTop + 12f, textPaint)
                canvas.drawText("--$unit", 5f, topChartBottom - 2f, textPaint)
            }
        }

        if (altPoints.size >= 2) {
            val minA = altPoints.minOf { it.second!! }
            val maxA = altPoints.maxOf { it.second!! }
            
            val bufferA = ((maxA - minA) * 0.3f).coerceAtLeast(10f)
            val drawMinA = minA - bufferA
            val drawMaxA = maxA + bufferA
            val rangeA = (drawMaxA - drawMinA)

            paint.color = Color.rgb(135, 206, 235)
            paint.strokeWidth = 8f
            val path = Path()
            fun getY(a: Float) = bottomChartBottom - ((a - drawMinA) / rangeA) * bottomChartHeight
            
            path.moveTo(getX(altPoints[0].first), getY(altPoints[0].second!!))
            for (i in 1 until altPoints.size) {
                val x1 = getX(altPoints[i - 1].first)
                val y1 = getY(altPoints[i - 1].second!!)
                val x2 = getX(altPoints[i].first)
                val y2 = getY(altPoints[i].second!!)
                path.cubicTo((x1 + x2) / 2, y1, (x1 + x2) / 2, y2, x2, y2)
            }
            canvas.drawPath(path, paint)

            val formatAlt = { a: Float -> 
                val displayA = if (isMetric) a else (a * 3.28084f)
                val unit = if (isMetric) "m" else "ft"
                "${ceil(displayA.toDouble()).toInt()}$unit"
            }
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 12f
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(formatAlt(maxA), width - 5f, bottomChartTop + 12f, textPaint)
            canvas.drawText(formatAlt(minA), width - 5f, bottomChartBottom - 2f, textPaint)
        } else {
            // Placeholder legends for Altitude
            val unit = if (isMetric) "m" else "ft"
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 12f
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("--$unit", width - 5f, bottomChartTop + 12f, textPaint)
            canvas.drawText("--$unit", width - 5f, bottomChartBottom - 2f, textPaint)
        }

        // Show "Waiting for data..." if no points are available yet
        if (tempPoints.size < 2 && altPoints.size < 2) {
            val waitPaint = Paint().apply {
                color = Color.WHITE
                textSize = 24f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }
            canvas.drawText("Waiting for data...", width / 2f, middleY + 8f, waitPaint)
        }

        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BLE Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        shouldAutoReconnect = false
        handler.removeCallbacksAndMessages(null)
        locationManager?.removeUpdates(locationListener)
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
