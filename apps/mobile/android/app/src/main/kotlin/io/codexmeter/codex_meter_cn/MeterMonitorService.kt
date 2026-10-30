package io.codexmeter.codex_meter_cn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MeterMonitorService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var task: ScheduledFuture<*>? = null
    private var minutes = 3
    private var lastSource: Pair<String,String>? = null
    @Volatile private var destroyed=false
    companion object {
        const val NOTIFICATION_ID = 40
        fun sync(context: Context, fromVisibleApp: Boolean = false) {
            val config = try { MeterStore.config(context) } catch (_: Exception) { null }
            if (config == null || MeterStore.source(config) == null || !config.optBoolean("backgroundRefresh", true)) {
                context.stopService(Intent(context, MeterMonitorService::class.java))
                return
            }
            // Android 15 forbids starting dataSync services from BOOT_COMPLETED.
            if (!fromVisibleApp) return
            try {
                val intent = Intent(context, MeterMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (_: Exception) {
                MeterStore.prefs(context).edit().putString("monitorState", "restricted").apply()
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = try { MeterStore.config(this) } catch (_: Exception) { null }
        if (config == null || MeterStore.source(config) == null || !config.optBoolean("backgroundRefresh", true)) {
            stopSelf(); return START_NOT_STICKY
        }
        val interval=config.optInt("refreshMinutes", 3).takeIf { it in setOf(1, 3, 5, 10) } ?: 3
        val source=MeterStore.source(config)
        if(task!=null && minutes==interval && source==lastSource)return START_STICKY
        minutes=interval;lastSource=source
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("monitor", "自动额度监测", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification())
        MeterStore.prefs(this).edit().putString("monitorState", "running").apply()
        task?.cancel(false)
        var initial=true
        task = executor.scheduleWithFixedDelay({
            try {
                if(!initial) {
                    val prefs=MeterStore.prefs(this)
                    prefs.edit().putLong("lastAutomaticTick",System.currentTimeMillis()/1000).putInt("automaticRefreshCount",prefs.getInt("automaticRefreshCount",0)+1).apply()
                }
                initial=false
                MeterSync.refresh(this)
                if(!destroyed)manager.notify(NOTIFICATION_ID, notification())
            } catch (_: Exception) { /* Next attempt retains the last snapshot. */ }
        }, 0, minutes.toLong(), TimeUnit.MINUTES)
        return START_STICKY
    }
    private fun notification(): android.app.Notification {
        val prefs = MeterStore.prefs(this)
        val snapshot = try { prefs.getString("snapshot", null)?.let { SnapshotProtocol.parse(it) } } catch (_: Exception) { null }
        val (summary, _) = QuotaTileSummary.text(snapshot, System.currentTimeMillis() / 1000, prefs.getBoolean("lastError", false))
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "monitor").setSmallIcon(R.drawable.ic_quota_tile)
            .setContentTitle(summary).setContentText("Codex · 每 $minutes 分钟自动更新 · 点击管理")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        MeterStore.prefs(this).edit().putString("monitorState", "timeLimit").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        destroyed=true
        task?.cancel(true)
        executor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (MeterStore.prefs(this).getString("monitorState", "") == "running") {
            MeterStore.prefs(this).edit().putString("monitorState", "stopped").apply()
        }
        super.onDestroy()
    }
}
