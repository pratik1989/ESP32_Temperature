package com.example.esp32temperature

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class TemperatureWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, "--", "--")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == BleForegroundService.ACTION_UPDATE_DATA) {
            val temp = intent.getStringExtra(BleForegroundService.EXTRA_TEMPERATURE) ?: "--"
            val alt = intent.getStringExtra(BleForegroundService.EXTRA_ALTITUDE) ?: "--"
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TemperatureWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId, temp, alt)
            }
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, temp: String, alt: String) {
        val views = RemoteViews(context.packageName, R.layout.temperature_widget_layout)
        views.setTextViewText(R.id.widget_temperature, "Temperature: $temp °C")
        views.setTextViewText(R.id.widget_altitude, "Altitude: $alt ft")
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
