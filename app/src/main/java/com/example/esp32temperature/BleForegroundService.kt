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
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import java.util.*

@SuppressLint("MissingPermission")
class BleForegroundService : Service() {

    private val TAG = "BleForegroundService"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var locationManager: LocationManager? = null

    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val CHANNEL_ID = "BleServiceChannel"
    private val NOTIFICATION_ID = 1

    private var currentTemperature = "--"
    private var currentAltitude = "--"
    
    private var connectionStartTime: Long = 0
    private var isDataRecordingEnabled = false
    
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
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESP32 Sensor Monitor")
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
                val altitudeFeet = location.altitude * 3.28084
                currentAltitude = String.format("%.1f", altitudeFeet)
                recordData()
            }
        }
        override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {}
    }

    private fun startScanning() {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        isScanning = true
        broadcastStatus("Scanning", "")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: ""
            if (name.contains("ESP32_Te", ignoreCase = true)) {
                stopScanning()
                connectToDevice(result.device)
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
        broadcastStatus("Connecting", device.address)
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcastStatus("Connected", gatt.device.address)
                connectionStartTime = System.currentTimeMillis()
                isDataRecordingEnabled = false
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                broadcastStatus("Disconnected", "")
                currentTemperature = "--"
                isDataRecordingEnabled = false
                dataHistory.clear()
                updateWidgetAndBroadcast()
                android.os.Handler(mainLooper).postDelayed({ startScanning() }, 5000)
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
        if (!isDataRecordingEnabled && connectionStartTime > 0 && (now - connectionStartTime) > 3000) {
            isDataRecordingEnabled = true
        }

        if (isDataRecordingEnabled) {
            val tempFloat = currentTemperature.toFloatOrNull()
            val altFloat = currentAltitude.toFloatOrNull()
            dataHistory.add(ChartPoint(now, tempFloat, altFloat))
            dataHistory.removeAll { now - it.timestamp > HISTORY_LIMIT_MS }
        }
        updateWidgetAndBroadcast()
    }

    private fun updateWidgetAndBroadcast() {
        val chartBitmap = drawChart()
        
        // Update Widget
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val thisWidget = ComponentName(this, TemperatureWidgetProvider::class.java)
        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
        
        val views = RemoteViews(packageName, R.layout.temperature_widget_layout)
        views.setTextViewText(R.id.widget_temperature, "Temperature: $currentTemperature °C")
        views.setTextViewText(R.id.widget_altitude, "Altitude: $currentAltitude ft")
        if (chartBitmap != null) {
            views.setImageViewBitmap(R.id.widget_chart, chartBitmap)
        }
        appWidgetManager.updateAppWidget(allWidgetIds, views)

        // Broadcast to App - Compress bitmap to avoid Binder Transaction limits
        val intent = Intent(ACTION_UPDATE_DATA)
        intent.putExtra(EXTRA_TEMPERATURE, currentTemperature)
        intent.putExtra(EXTRA_ALTITUDE, currentAltitude)
        
        if (chartBitmap != null) {
            val stream = java.io.ByteArrayOutputStream()
            chartBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            intent.putExtra(EXTRA_CHART_BYTES, stream.toByteArray())
        }
        
        intent.setPackage(packageName)
        sendBroadcast(intent)
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

        val tempPoints = dataHistory.filter { it.temp != null && it.timestamp >= chartStartTime }
        val altPoints = dataHistory.filter { it.alt != null && it.timestamp >= chartStartTime }
        
        if (tempPoints.isEmpty() && altPoints.isEmpty()) return bitmap

        fun getAdjustedBounds(points: List<Float>, defaultMin: Float, defaultMax: Float, minBuffer: Float): Pair<Float, Float> {
            if (points.isEmpty()) return defaultMin to defaultMax
            val min = points.min()
            val max = points.max()
            val range = (max - min).coerceAtLeast(minBuffer)
            return (min - range * 0.5f) to (max + range * 0.5f)
        }

        val (minT, maxT) = getAdjustedBounds(tempPoints.map { it.temp!! }, 0f, 100f, 10f)
        val (minA, maxA) = getAdjustedBounds(altPoints.map { it.alt!! }, 0f, 10000f, 100f)

        val padding = 20f
        val chartW = width - 2 * padding
        val chartH = height - 2 * padding

        fun getX(ts: Long) = padding + ((ts - chartStartTime).toFloat() / totalRange) * chartW
        fun getY(value: Float, min: Float, max: Float): Float {
            val range = if (max == min) 1f else max - min
            return height - padding - ((value - min) / range) * chartH
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
            path.moveTo(getX(tempPoints[0].timestamp), getY(tempPoints[0].temp!!, minT, maxT))
            for (i in 1 until tempPoints.size) {
                path.lineTo(getX(tempPoints[i].timestamp), getY(tempPoints[i].temp!!, minT, maxT))
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
            path.moveTo(getX(altPoints[0].timestamp), getY(altPoints[0].alt!!, minA, maxA))
            for (i in 1 until altPoints.size) {
                path.lineTo(getX(altPoints[i].timestamp), getY(altPoints[i].alt!!, minA, maxA))
            }
            canvas.drawPath(path, altPaint)
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 12f
            isFakeBoldText = true
        }
        canvas.drawText("${String.format("%.1f", maxT)}°C", 5f, padding, textPaint)
        canvas.drawText("${String.format("%.1f", minT)}°C", 5f, height - 5f, textPaint)
        
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${String.format("%.0f", maxA)}ft", width - 5f, padding, textPaint)
        canvas.drawText("${String.format("%.0f", minA)}ft", width - 5f, height - 5f, textPaint)

        return bitmap
    }

    private fun broadcastStatus(status: String, deviceId: String) {
        val intent = Intent(ACTION_CONNECTION_STATUS)
        intent.putExtra(EXTRA_STATUS, status)
        intent.putExtra(EXTRA_DEVICE_ID, deviceId)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BLE Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopScanning()
        locationManager?.removeUpdates(locationListener)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
