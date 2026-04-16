package com.example.esp32temperature

import android.annotation.SuppressLint
import android.app.*
import android.appwidget.AppWidgetManager
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

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

    private val CHANNEL_ID = "BleServiceChannel"
    private val NOTIFICATION_ID = 1

    private var currentTemperature = "--"
    private var currentAltitude = "--" 
    private var currentStatus = "Disconnected"
    private var connectedDeviceId = ""

    private var connectionStartTime: Long = 0
    
    data class ChartPoint(val timestamp: Long, val temp: Float?, val alt: Float?)
    private val dataHistory = mutableListOf<ChartPoint>()
    private val HISTORY_LIMIT_MS = 3600000L // 1 hour

    companion object {
        const val ACTION_UPDATE_DATA = "com.example.esp32temperature.UPDATE_DATA"
        const val ACTION_CONNECTION_STATUS = "com.example.esp32temperature.CONNECTION_STATUS"
        const val EXTRA_TEMPERATURE = "EXTRA_TEMPERATURE" 
        const val EXTRA_ALTITUDE = "EXTRA_ALTITUDE" 
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"
        const val EXTRA_CHART_BYTES = "EXTRA_CHART_BYTES"
        
        const val PREFS_NAME = "ESP32Prefs"
        const val KEY_IS_METRIC = "is_metric"
        const val KEY_IS_CELSIUS = "is_celsius"
        const val KEY_SHOW_ALT_ONLY = "show_alt_only"
        const val KEY_LOCKED_DEVICE_ID = "locked_device_id"
        const val KEY_IS_LOCKED = "is_locked"
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shouldAutoReconnect = true
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DMD Temperature Monitor")
            .setContentText("Monitoring Temperature & Altitude...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        startScanning()
        startLocationUpdates()
        
        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, locationListener)
        } catch (e: SecurityException) { }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasAltitude()) {
                currentAltitude = String.format("%.1f", location.altitude)
                recordData()
            }
        }
        override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {}
    }

    private fun startScanning() {
        if (isScanning || bluetoothGatt != null) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        isScanning = true
        broadcastStatus("Scanning", "")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: ""
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isLocked = prefs.getBoolean(KEY_IS_LOCKED, false)
            val lockedId = prefs.getString(KEY_LOCKED_DEVICE_ID, "")

            if (name.contains("ESP32_Te", ignoreCase = true)) {
                if (isLocked && lockedId!!.isNotEmpty()) {
                    if (result.device.address == lockedId) {
                        stopScanning()
                        connectToDevice(result.device)
                    }
                } else {
                    stopScanning()
                    connectToDevice(result.device)
                }
            }
        }
    }

    private fun stopScanning() {
        if (isScanning) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (bluetoothGatt != null) {
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        broadcastStatus("Connecting", device.address)
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcastStatus("Connected", gatt.device.address)
                connectionStartTime = System.currentTimeMillis()
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt == bluetoothGatt) {
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
                broadcastStatus("Disconnected", "")
                currentTemperature = "--"
                updateWidgetAndBroadcast()
                if (shouldAutoReconnect) {
                    handler.postDelayed({ startScanning() }, 5000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                currentTemperature = characteristic.value?.let { String(it) } ?: "--"
                recordData()
            }
        }
    }

    private fun recordData() {
        val now = System.currentTimeMillis()
        val tempFloat = currentTemperature.toFloatOrNull()
        val altFloat = currentAltitude.toFloatOrNull()
        
        if (tempFloat != null || altFloat != null) {
            dataHistory.add(ChartPoint(now, tempFloat, altFloat))
            dataHistory.removeAll { now - it.timestamp > HISTORY_LIMIT_MS }
        }
        updateWidgetAndBroadcast()
    }

    private fun updateWidgetAndBroadcast() {
        val chartBitmap = drawChart()
        
        // Broadcast for Activity
        val activityIntent = Intent(ACTION_UPDATE_DATA)
        activityIntent.putExtra(EXTRA_TEMPERATURE, currentTemperature)
        activityIntent.putExtra(EXTRA_ALTITUDE, currentAltitude)
        activityIntent.putExtra(EXTRA_STATUS, currentStatus)
        activityIntent.putExtra(EXTRA_DEVICE_ID, connectedDeviceId)

        val stream = java.io.ByteArrayOutputStream()
        chartBitmap?.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val chartBytes = stream.toByteArray()
        if (chartBytes.isNotEmpty()) {
            activityIntent.putExtra(EXTRA_CHART_BYTES, chartBytes)
        }
        activityIntent.setPackage(packageName)
        sendBroadcast(activityIntent)

        // Directly notify WidgetProvider
        val widgetIntent = Intent(this, TemperatureWidgetProvider::class.java)
        widgetIntent.action = ACTION_UPDATE_DATA
        widgetIntent.putExtra(EXTRA_TEMPERATURE, currentTemperature)
        widgetIntent.putExtra(EXTRA_ALTITUDE, currentAltitude)
        widgetIntent.putExtra(EXTRA_STATUS, currentStatus)
        widgetIntent.putExtra(EXTRA_DEVICE_ID, connectedDeviceId)
        if (chartBytes.isNotEmpty()) {
            widgetIntent.putExtra(EXTRA_CHART_BYTES, chartBytes)
        }
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
        
        // Also notify widget provider
        val widgetIntent = Intent(this, TemperatureWidgetProvider::class.java)
        widgetIntent.action = ACTION_CONNECTION_STATUS
        widgetIntent.putExtra(EXTRA_STATUS, status)
        widgetIntent.putExtra(EXTRA_DEVICE_ID, deviceId)
        sendBroadcast(widgetIntent)
    }

    private fun drawChart(): Bitmap? {
        if (dataHistory.size < 2) return null
        
        val width = 400
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val now = System.currentTimeMillis()
        val dataStartTime = dataHistory.firstOrNull()?.timestamp ?: now
        val chartStartTime = Math.max(now - HISTORY_LIMIT_MS, dataStartTime)
        val totalRange = Math.max(10000L, now - chartStartTime)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isMetric = prefs.getBoolean(KEY_IS_METRIC, true)
        val isCelsius = prefs.getBoolean(KEY_IS_CELSIUS, true)
        val showAltOnly = prefs.getBoolean(KEY_SHOW_ALT_ONLY, false)

        val tempPoints = dataHistory.filter { it.temp != null && it.timestamp >= chartStartTime }
        val altPoints = dataHistory.filter { it.alt != null && it.timestamp >= chartStartTime }
        
        if (tempPoints.isEmpty() && altPoints.isEmpty()) return bitmap

        fun getAdjustedBounds(points: List<Float>, defaultMin: Float, defaultMax: Float, minBuffer: Float, bufferPercent: Float): Pair<Float, Float> {
            if (points.isEmpty()) return defaultMin to defaultMax
            val min = points.min()
            val max = points.max()
            val range = (max - min).coerceAtLeast(minBuffer)
            return (min - range * bufferPercent) to (max + range * bufferPercent)
        }

        val padding = 20f
        val chartW = width - 2 * padding

        fun getX(ts: Long) = padding + ((ts - chartStartTime).toFloat() / totalRange) * chartW

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 10f
            isFakeBoldText = true
        }

        if (showAltOnly) {
            // Full screen Altitude chart with larger buffer
            val (minA, maxA) = getAdjustedBounds(altPoints.map { it.alt!! }, 0f, 10000f, 20f, 0.3f)
            val chartH = height - 2 * padding - 15f // leaving space for time info

            fun getFullAltY(value: Float, min: Float, max: Float): Float {
                val range = if (max == min) 1f else max - min
                return height - padding - 15f - ((value - min) / range) * chartH
            }

            val altPaint = Paint().apply {
                color = Color.rgb(135, 206, 235)
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            if (altPoints.size >= 2) {
                val path = Path()
                path.moveTo(getX(altPoints[0].timestamp), getFullAltY(altPoints[0].alt!!, minA, maxA))
                for (i in 1 until altPoints.size) {
                    path.lineTo(getX(altPoints[i].timestamp), getFullAltY(altPoints[i].alt!!, minA, maxA))
                }
                canvas.drawPath(path, altPaint)
            }

            textPaint.textAlign = Paint.Align.RIGHT
            val altMaxStr = if (isMetric) String.format("%.0fm", maxA) else String.format("%.0fft", maxA * 3.28084f)
            val altMinStr = if (isMetric) String.format("%.0fm", minA) else String.format("%.0fft", minA * 3.28084f)
            canvas.drawText(altMaxStr, width - 5f, padding + 10f, textPaint)
            canvas.drawText(altMinStr, width - 5f, height - padding - 15f, textPaint)

        } else {
            // Split screen chart with small buffer
            val (minT, maxT) = getAdjustedBounds(tempPoints.map { it.temp!! }, 0f, 100f, 2f, 0.1f)
            val (minA, maxA) = getAdjustedBounds(altPoints.map { it.alt!! }, 0f, 10000f, 20f, 0.1f)
            val halfH = (height - 2 * padding - 15f) / 2f

            // Temperature on TOP half
            fun getTempY(value: Float, min: Float, max: Float): Float {
                val range = if (max == min) 1f else max - min
                return padding + halfH - ((value - min) / range) * halfH
            }

            // Altitude on BOTTOM half
            fun getAltY(value: Float, min: Float, max: Float): Float {
                val range = if (max == min) 1f else max - min
                return height - padding - 15f - ((value - min) / range) * halfH
            }

            val tempPaint = Paint().apply {
                color = Color.MAGENTA
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            if (tempPoints.size >= 2) {
                val path = Path()
                path.moveTo(getX(tempPoints[0].timestamp), getTempY(tempPoints[0].temp!!, minT, maxT))
                for (i in 1 until tempPoints.size) {
                    path.lineTo(getX(tempPoints[i].timestamp), getTempY(tempPoints[i].temp!!, minT, maxT))
                }
                canvas.drawPath(path, tempPaint)
            }

            val altPaint = Paint().apply {
                color = Color.rgb(135, 206, 235)
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            if (altPoints.size >= 2) {
                val path = Path()
                path.moveTo(getX(altPoints[0].timestamp), getAltY(altPoints[0].alt!!, minA, maxA))
                for (i in 1 until altPoints.size) {
                    path.lineTo(getX(altPoints[i].timestamp), getAltY(altPoints[i].alt!!, minA, maxA))
                }
                canvas.drawPath(path, altPaint)
            }

            // Temperature Labels
            textPaint.textAlign = Paint.Align.LEFT
            val tempMaxStr = if (isCelsius) String.format("%.1f°C", maxT) else String.format("%.1f°F", maxT * 9/5 + 32)
            val tempMinStr = if (isCelsius) String.format("%.1f°C", minT) else String.format("%.1f°F", minT * 9/5 + 32)
            canvas.drawText(tempMaxStr, 5f, padding + 10f, textPaint)
            canvas.drawText(tempMinStr, 5f, padding + halfH, textPaint)
            
            // Altitude Labels
            textPaint.textAlign = Paint.Align.RIGHT
            val altMaxStr = if (isMetric) String.format("%.0fm", maxA) else String.format("%.0fft", maxA * 3.28084f)
            val altMinStr = if (isMetric) String.format("%.0fm", minA) else String.format("%.0fft", minA * 3.28084f)
            canvas.drawText(altMaxStr, width - 5f, padding + halfH + 10f, textPaint)
            canvas.drawText(altMinStr, width - 5f, height - padding - 15f, textPaint)

            // Draw separator line
            val sepPaint = Paint().apply {
                color = Color.DKGRAY
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(padding, padding + halfH, width - padding, padding + halfH, sepPaint)
        }

        // Time Info (drawn at the very bottom)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val elapsedMs = now - dataStartTime
        
        textPaint.color = Color.LTGRAY
        textPaint.textSize = 9f
        
        if (elapsedMs < HISTORY_LIMIT_MS) {
            val elapsedMin = elapsedMs / 60000
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("Started: ${timeFormat.format(Date(dataStartTime))}", 5f, height - 5f, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("$elapsedMin min elapsed", width - 5f, height - 5f, textPaint)
        } else {
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("Start: ${timeFormat.format(Date(chartStartTime))}", 5f, height - 5f, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Now: ${timeFormat.format(Date(now))}", width - 5f, height - 5f, textPaint)
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
        broadcastStatus("Disconnected", "") // Send final status before closing
        stopScanning()
        locationManager?.removeUpdates(locationListener)
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
