package io.codexmeter.codex_meter_cn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MeterWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)
    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.meter_widget)
            val now = System.currentTimeMillis() / 1000
            val prefs = MeterStore.prefs(context)
            val snapshot = try { prefs.getString("snapshot", null)?.let { SnapshotProtocol.parse(it) } } catch (_: Exception) { null }
            if (snapshot != null) {
                val windows = snapshot.getJSONObject("windows")
                fun percent(name: String): String {
                    val window = windows.optJSONObject(name) ?: return "—"
                    return "${(100 - window.getDouble("usedPercent")).roundToInt()}%"
                }
                val stale = snapshot.getLong("expiresAt") <= now || listOf("fiveHour", "weekly").any {
                    SnapshotProtocol.reset(windows.optJSONObject(it))?.let { reset -> reset <= now } == true
                }
                views.setTextViewText(R.id.widget_quotas, "5h ${percent("fiveHour")}   周 ${percent("weekly")}")
                views.setTextViewText(R.id.widget_resets, "5h ${SnapshotProtocol.countdown(windows.optJSONObject("fiveHour"), now)} · 周 ${SnapshotProtocol.countdown(windows.optJSONObject("weekly"), now)}")
                val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(snapshot.getLong("collectedAt") * 1000))
                val status = if (stale) "已过期 · 上次剩余" else if (prefs.getBoolean("lastError", false)) "连接异常 · 上次剩余" else "剩余额度"
                views.setTextViewText(R.id.widget_status, "$status · $time")
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            manager.updateAppWidget(manager.getAppWidgetIds(ComponentName(context, MeterWidget::class.java)), views)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val config = MeterStore.config(context) ?: return
            if (!config.optBoolean("notifications") || MeterStore.source(config) == null) return
            val name = intent.getStringExtra("window") ?: return
            val raw = MeterStore.prefs(context).getString("snapshot", null) ?: return
            val reset = SnapshotProtocol.reset(SnapshotProtocol.parse(raw).getJSONObject("windows").optJSONObject(name)) ?: return
            if (reset > System.currentTimeMillis() / 1000) return
            val id = "$name:$reset"
            if (MeterStore.prefs(context).getString("${name}_reset", null) == id) return
            val label = if (name == "fiveHour") "5 小时" else "每周"
            if (MeterStore.notify(context, if (name == "fiveHour") 20 else 21, "$label 预计重置时间已到", "请打开应用刷新，确认额度是否恢复。离线快照无法确认重置。")) {
                MeterStore.prefs(context).edit().putString("${name}_reset", id).apply()
            }
            MeterWidget.updateAll(context)
        } catch (_: Exception) { /* Keystore may be locked until the device is unlocked. */ }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val config = MeterStore.config(context) ?: return
            MeterStore.scheduleWork(context, config)
            MeterStore.prefs(context).getString("snapshot", null)?.let { MeterStore.remind(context, config, SnapshotProtocol.parse(it)) }
            MeterWidget.updateAll(context)
        } catch (_: Exception) { /* Opening the app restores scheduling if the Keystore was locked. */ }
    }
}
