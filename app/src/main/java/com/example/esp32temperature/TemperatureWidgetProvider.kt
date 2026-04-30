package com.example.esp32temperature

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import java.text.NumberFormat
import java.util.*
import kotlin.math.ceil

class TemperatureWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == BleForegroundService.ACTION_UPDATE_DATA || intent.action == BleForegroundService.ACTION_CONNECTION_STATUS) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TemperatureWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId, intent)
            }
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, intent: Intent?) {
        val prefs = context.getSharedPreferences(BleForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
        val isMetric = prefs.getBoolean(BleForegroundService.KEY_IS_METRIC, true)
        val isCelsius = prefs.getBoolean(BleForegroundService.KEY_IS_CELSIUS, true)
        val tempSource = prefs.getInt(BleForegroundService.KEY_TEMP_SOURCE, 0) // 0: DS, 1: BMP
        val altSource = prefs.getInt(BleForegroundService.KEY_ALT_SOURCE, 0)   // 0: GPS, 1: BMP

        val status = intent?.getStringExtra(BleForegroundService.EXTRA_STATUS) ?: "Disconnected"
        val lastUpdate = intent?.getStringExtra(BleForegroundService.EXTRA_LAST_UPDATE_TIME) ?: ""
        
        val dsTemp = intent?.getStringExtra(BleForegroundService.EXTRA_DS_TEMP) ?: "--"
        val bmpTemp = intent?.getStringExtra(BleForegroundService.EXTRA_BMP_TEMP) ?: "--"
        val bmpAlt = intent?.getStringExtra(BleForegroundService.EXTRA_BMP_ALT) ?: "--"
        val gpsAlt = intent?.getStringExtra(BleForegroundService.EXTRA_GPS_ALT) ?: "--"

        val selectedTemp = if (tempSource == 0) dsTemp else bmpTemp
        val selectedAlt = if (altSource == 0) gpsAlt else bmpAlt

        val displayTemp = if (selectedTemp == "--") "--" else {
            val t = selectedTemp.toFloatOrNull() ?: 0f
            if (isCelsius) String.format("%.1f °C", t) else String.format("%.1f °F", t * 9/5 + 32)
        }
        
        val displayAlt = if (selectedAlt == "--") "--" else {
            val a = selectedAlt.toFloatOrNull() ?: 0f
            val value = if (isMetric) a else a * 3.28084f
            val roundedValue = ceil(value).toInt()
            val formatter = NumberFormat.getInstance(Locale("en", "IN"))
            val formatted = formatter.format(roundedValue)
            if (isMetric) "$formatted m" else "$formatted ft"
        }

        val views = RemoteViews(context.packageName, R.layout.temperature_widget_layout)
        
        views.setTextViewText(R.id.widget_status, status)
        val statusColor = if (status == "Connected") 0xFF4CAF50.toInt() else 0xFFFF0000.toInt()
        views.setTextColor(R.id.widget_status, statusColor)

        val mainIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, mainIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

        if (lastUpdate.isNotEmpty()) {
            views.setTextViewText(R.id.widget_temp_time, " ($lastUpdate)")
            views.setViewVisibility(R.id.widget_temp_time, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_temp_time, android.view.View.GONE)
        }

        views.setTextViewText(R.id.widget_temp_value, displayTemp)
        views.setTextViewText(R.id.widget_alt_value, displayAlt)

        val chartBytes = intent?.getByteArrayExtra(BleForegroundService.EXTRA_CHART_BYTES)
        if (chartBytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(chartBytes, 0, chartBytes.size)
            views.setImageViewBitmap(R.id.widget_chart, bitmap)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
