package eu.kanade.tachiyomi.appwidget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.setWidgetPreviews

class TachiyomiWidgetManager {
    @SuppressLint("CheckResult")
    suspend fun Context.init() {
        val manager = GlanceAppWidgetManager(this)
        if (manager.getGlanceIds(UpdatesGridGlanceWidget::class.java).isNotEmpty()) {
            UpdatesGridGlanceWidget().loadData()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            manager.setWidgetPreviews<UpdatesGridGlanceReceiver>()
        }
    }
}
