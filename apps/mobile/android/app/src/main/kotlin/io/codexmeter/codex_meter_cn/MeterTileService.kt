package io.codexmeter.codex_meter_cn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import kotlin.math.roundToInt

object QuotaTileSummary {
    fun text(snapshot: JSONObject?, now: Long, offline: Boolean): Pair<String, Boolean> {
        if (snapshot == null) return "请先连接数据源" to false
        val windows = snapshot.getJSONObject("windows")
        val historical = snapshot.getLong("expiresAt") <= now || offline ||
            listOf("fiveHour", "weekly").any { SnapshotProtocol.reset(windows.optJSONObject(it))?.let { reset -> reset <= now } == true }
        fun remaining(name: String): String = windows.optJSONObject(name)?.let {
            "${(100 - it.getDouble("usedPercent")).roundToInt()}%"
        } ?: "—"
        val available = !windows.isNull("fiveHour") || !windows.isNull("weekly")
        return ((if (historical) "上次 " else "") + "5h ${remaining("fiveHour")} · 周 ${remaining("weekly")}") to (!historical && available)
    }
}

class MeterTileService : TileService() {
    private val changes = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if(key=="snapshot" || key=="lastError") render()
    }
    companion object {
        fun update(context: Context) = requestListeningState(context, ComponentName(context, MeterTileService::class.java))
    }
    override fun onStartListening() {
        super.onStartListening()
        MeterStore.prefs(this).registerOnSharedPreferenceChangeListener(changes)
        render()
    }
    override fun onStopListening() {
        MeterStore.prefs(this).unregisterOnSharedPreferenceChangeListener(changes)
        super.onStopListening()
    }
    private fun render() {
        val prefs = MeterStore.prefs(this)
        val snapshot = try { prefs.getString("snapshot", null)?.let { SnapshotProtocol.parse(it) } } catch (_: Exception) { null }
        val (text, fresh) = QuotaTileSummary.text(snapshot, System.currentTimeMillis() / 1000, prefs.getBoolean("lastError", false))
        qsTile?.apply {
            // Xiaomi may hide subtitles entirely; the icon must carry the values too.
            label = if (snapshot == null) "Codex 额度" else text
            if (Build.VERSION.SDK_INT >= 29) subtitle = "Codex 剩余额度"
            icon = quotaIcon(snapshot, fresh)
            contentDescription = "Codex 剩余额度，$text，点击刷新，长按打开应用"
            state = if (fresh) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
    private fun quotaIcon(snapshot: JSONObject?, fresh: Boolean): Icon {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (fresh || snapshot == null) 43f else 36f
        }
        val windows = snapshot?.getJSONObject("windows")
        fun value(name: String) = windows?.optJSONObject(name)?.let { "${(100 - it.getDouble("usedPercent")).roundToInt()}%" } ?: "—"
        if (!fresh && snapshot != null) {
            paint.textSize = 20f
            canvas.drawText("上次", 48f, 19f, paint)
            paint.textSize = 36f
        }
        canvas.drawText(value("fiveHour"), 48f, if (fresh || snapshot == null) 42f else 53f, paint)
        canvas.drawText(value("weekly"), 48f, 91f, paint)
        return Icon.createWithBitmap(bitmap)
    }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { refresh() } else refresh()
    }
    private fun refresh() {
        val configured = try { MeterStore.config(this)?.let { MeterStore.source(it) } != null } catch (_: Exception) { false }
        if (!configured) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
            return
        }
        if (Build.VERSION.SDK_INT >= 29) qsTile?.subtitle = "正在刷新…"
        qsTile?.updateTile()
        WorkManager.getInstance(this).enqueueUniqueWork("meter_tile_refresh", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MeterRefreshWorker>().build())
    }
}
