package com.example.esp32temperature

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

@SuppressLint("MissingPermission")
class BleForegroundService : Service() {

    private val TAG = "BleForegroundService"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val CHANNEL_ID = "BleServiceChannel"
    private val NOTIFICATION_ID = 1

    companion object {
        const val ACTION_UPDATE_TEMPERATURE = "com.example.esp32temperature.UPDATE_TEMPERATURE"
        const val ACTION_CONNECTION_STATUS = "com.example.esp32temperature.CONNECTION_STATUS"
        const val EXTRA_TEMPERATURE = "EXTRA_TEMPERATURE"
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESP32 Temperature Monitor")
            .setContentText("Scanning for ESP32...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        if (bluetoothAdapter?.isEnabled == false) {
            Log.e(TAG, "Bluetooth is disabled")
            broadcastStatus("Disconnected", "")
            return START_NOT_STICKY
        }

        startScanning()
        
        return START_STICKY
    }

    private fun startScanning() {
        if (isScanning) {
            Log.d(TAG, "Already scanning, ignoring request.")
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null")
            return
        }

        Log.d(TAG, "Starting Scan...")
        isScanning = true
        broadcastStatus("Scanning", "")
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, scanSettings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: "Unknown"
            // Using contains to handle truncated names like "ESP32_Te"
            if (deviceName.contains("ESP32_Te", ignoreCase = true)) {
                Log.i(TAG, "Match found: $deviceName (${result.device.address}). Connecting...")
                stopScanning()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "Scan failed with error code: $errorCode")
            broadcastStatus("Scan Failed ($errorCode)", "")
        }
    }

    private fun stopScanning() {
        if (isScanning) {
            Log.d(TAG, "Stopping Scan")
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to GATT server...")
        broadcastStatus("Connecting", device.address)
        // Using TRANSPORT_LE for better compatibility with BLE devices
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed. Status: $status, New State: $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server. Discovering services...")
                broadcastStatus("Connected", gatt.device.address)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from GATT server.")
                broadcastStatus("Disconnected", "")
                broadcastTemperature("--")
                isScanning = false
                // Attempt to reconnect by restarting scan after 5 seconds
                android.os.Handler(mainLooper).postDelayed({ startScanning() }, 5000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered successfully")
                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "Service NOT found: $SERVICE_UUID")
                    return
                }
                
                val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    Log.i(TAG, "Characteristic found. Enabling notifications...")
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
                val data = characteristic.value
                val temperature = data?.let { String(it) } ?: "--"
                broadcastTemperature(temperature)
            }
        }
    }

    private fun broadcastTemperature(temperature: String) {
        val intent = Intent(ACTION_UPDATE_TEMPERATURE)
        intent.putExtra(EXTRA_TEMPERATURE, temperature)
        intent.setPackage(packageName)
        sendBroadcast(intent)
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
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "BLE Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service Destroyed")
        stopScanning()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
