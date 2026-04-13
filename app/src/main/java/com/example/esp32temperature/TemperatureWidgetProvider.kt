package com.example.esp32temperature

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews

class TemperatureWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, "--", "--", "Disconnected", null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == BleForegroundService.ACTION_UPDATE_DATA || intent.action == BleForegroundService.ACTION_CONNECTION_STATUS) {
            val status = intent.getStringExtra(BleForegroundService.EXTRA_STATUS) ?: "Disconnected"
            val temp = intent.getStringExtra(BleForegroundService.EXTRA_TEMPERATURE) ?: "--"
            val alt = intent.getStringExtra(BleForegroundService.EXTRA_ALTITUDE) ?: "--"
            val chartBytes = intent.getByteArrayExtra(BleForegroundService.EXTRA_CHART_BYTES)
            val chartBitmap = chartBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TemperatureWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId, temp, alt, status, chartBitmap)
            }
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, temp: String, alt: String, status: String, chartBitmap: android.graphics.Bitmap?) {
        val prefs = context.getSharedPreferences(BleForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
        val isMetric = prefs.getBoolean(BleForegroundService.KEY_IS_METRIC, true)

        val displayTemp = if (temp == "--") "--" else {
            val t = temp.toFloatOrNull() ?: 0f
            String.format("%.1f °C", t)
        }
        
        val displayAlt = if (alt == "--") "--" else {
            val a = alt.toFloatOrNull() ?: 0f
            if (isMetric) String.format("%.1f m", a) else String.format("%.1f ft", a * 3.28084f)
        }

        val views = RemoteViews(context.packageName, R.layout.temperature_widget_layout)
        
        // Update connection status
        views.setTextViewText(R.id.widget_status, status)
        val statusColor = if (status == "Connected") 0xFF4CAF50.toInt() else 0xFFFF0000.toInt()
        views.setTextColor(R.id.widget_status, statusColor)

        // Add click listener to open the app
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

        views.setTextViewText(R.id.widget_temp_value, displayTemp)
        views.setTextViewText(R.id.widget_alt_value, displayAlt)

        if (chartBitmap != null) {
            views.setImageViewBitmap(R.id.widget_chart, chartBitmap)
        }
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
