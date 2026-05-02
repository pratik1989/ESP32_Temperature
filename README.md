<div align="center">
  <h1>🌡️ ESP32 Temperature & Altitude Monitor 🏔️</h1>
  <p><b>A professional Android application designed for adventure riders and outdoor enthusiasts.</b></p>
</div>

This app connects via Bluetooth Low Energy (BLE) to a custom ESP32-based sensor module to provide highly accurate, dual-source temperature and altitude data. It features real-time charting, background monitoring, and seamless integration with the DMD2 (Drive Mode Dashboard) launcher.

<hr>

## 🚀 Key Features

* **Dual-Sensor Temperature:** Monitor data from both `DS18B20` (external probe) and `BMP280` (internal) sensors.
* **Barometric & GPS Altitude:** Compare barometric altitude from the BMP280 with internal phone GPS data.
* **Real-time Visualization:** High-performance line charts that update in real-time.
* **DMD2 Integration:** Includes a dedicated Android Widget designed specifically to look great on the DMD2 home screen.
* **Foreground Service:** Runs as a persistent service, ensuring data collection continues even when the app is in the background.
* **Calibration Tools:** Advanced offset settings to calibrate your sensors for maximum accuracy.

## 📱 App Navigation & Interface

The app is designed with a clean, single-screen interface for easy use while on the move.

### 1. Data Display Boxes
* **Temperature Source:** A dropdown menu to select which sensor's data to prioritize for display (DS18B20 vs. BMP280).
* **Altitude Source:** Toggle between Barometric altitude (BMP280) and Internal GPS altitude.
* **Offset Boxes:**
  * *Temp Offset:* Apply a manual correction (positive or negative) to the temperature reading. Useful for compensating for engine heat or enclosure variance.
  * *Alt Offset:* Set a baseline altitude correction. This is essential for barometric sensors which vary with weather pressure.

### 2. Live Chart
The central box displays a dynamic line chart.
* **Top Half:** Shows the Temperature trend (Purple line).
* **Bottom Half:** Shows the Altitude trend (Light Blue line).

> **Note:** If **Alt Only** is toggled, the altitude chart expands to fill the entire view.

### 3. Action Buttons
* 🟢 <kbd>Refresh</kbd> Manually trigger a scan and reconnection attempt to the ESP32 module.
* 🔴 <kbd>Stop</kbd> Stops the background service, disconnects Bluetooth, and saves battery.
* 🏍️ <kbd>DMD2</kbd> *(Speedometer Icon)* Quickly launches the DMD2 application if installed.
* 🔵 <kbd>Info</kbd> Access developer information and support links.

### 4. Toggles (Bottom Row)
* **Meters/Feet:** Switch between Metric and Imperial units for altitude and offsets.
* **°C/°F:** Toggle between Celsius and Fahrenheit for temperature readings.
* **Alt Only:** When turned ON, it hides the temperature chart to provide a larger, more detailed view of your altitude changes.
* **Lock:** "Locks" the app to the currently connected device address. This prevents the app from accidentally connecting to other nearby sensors in a group riding scenario.

## 🛠 Hardware Compatibility

This app is built to communicate with an ESP32 flashed with the matching `MotoControl` firmware.
* **Sensors supported:** `DS18B20` (OneWire) and `BMP280` (I2C).
* **Communication:** BLE (Bluetooth 4.0+)

## 🎨 DMD2 Widget Setup

1. Press settings icon on the home screen.
2. Add system widget on any of the infomration containers on left or right side.
3. Select **ESP32 Temperature Widget** from the list.
4. The widget will now show your live sensor data and the trend chart directly on your dashboard.

   > **Note:** upon first sucessful connection with the sensor module, the module broadcasts data after 5 seconds and after that in every 30 seconds. If the app isn't able to connect automatically. Then Press the 'Stop' icon and then the 'Reload' icon to initiate pairing.

<hr>

## 👨‍💻 Developer

Made with Pride by **Pratik Kumar**
<br>
*Find more projects and support the journey:*

* 🎥 [YouTube - Sleepy Voyager](https://www.youtube.com/@sleepyvoyager) 
* 📸 [Instagram - @sleepyvoyager](https://instagram.com/sleepyvoyager)
* 💻 [GitHub Repository](#) <!-- Add your repo link here -->
