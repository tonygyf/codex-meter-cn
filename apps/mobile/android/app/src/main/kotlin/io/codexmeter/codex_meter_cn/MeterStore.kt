package io.codexmeter.codex_meter_cn

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object MeterStore {
    fun prefs(context: Context) = context.getSharedPreferences("meter", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("meter_config", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("meter_config", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(raw: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(raw.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun config(context: Context): JSONObject? {
        val encoded = prefs(context).getString("encryptedConfig", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    fun source(config: JSONObject): Pair<String, String>? = when(config.optString("mode", "demo")) {
        "worker" -> config.getString("workerUrl") to config.getString("workerToken")
        "direct" -> config.getString("directUrl") to config.getString("directToken")
        else -> null
    }
    @Synchronized fun save(context: Context, config: JSONObject) {
        require(config.optString("mode") in setOf("demo", "direct", "worker"))
        require(config.getInt("threshold") in 5..50)
        source(config)?.let { (url, token) -> SnapshotProtocol.endpoint(url); require(token.length in 32..1000 && !token.contains('\n') && !token.contains('\r')) }
        val editor = prefs(context).edit().putString("encryptedConfig", encrypt(config.toString()))
        // Source switches must not expose another account's cached quotas or alarms.
        editor.remove("snapshot").remove("fiveHour_low").remove("weekly_low").remove("lastError").commit()
        cancelAlarms(context)
        NotificationManagerCompat.from(context).cancelAll()
        scheduleWork(context, config)
        MeterWidget.updateAll(context)
        MeterTileService.update(context)
    }
    fun scheduleWork(context: Context, config: JSONObject) {
        val work = WorkManager.getInstance(context)
        if (source(config) == null) { work.cancelUniqueWork("meter_refresh"); return }
        val request = PeriodicWorkRequestBuilder<MeterRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        work.enqueueUniquePeriodicWork("meter_refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    @Synchronized fun accept(context: Context, raw: String, expectedSource: Pair<String, String>? = null) {
        val config = config(context) ?: return
        if (source(config) == null || expectedSource != null && source(config) != expectedSource) return
        val snapshot = SnapshotProtocol.parse(raw)
        prefs(context).edit().putString("snapshot", raw).putBoolean("lastError", false).commit()
        MeterWidget.updateAll(context)
        MeterTileService.update(context)
        remind(context, config, snapshot)
    }
    private fun alarmIntent(context: Context, index: Int): PendingIntent = PendingIntent.getBroadcast(context, 100 + index,
        Intent(context, ReminderReceiver::class.java).putExtra("window", if (index == 0) "fiveHour" else "weekly"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancelAlarms(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (index in 0..1) alarms.cancel(alarmIntent(context, index))
    }
    fun remind(context: Context, config: JSONObject, snapshot: JSONObject) {
        cancelAlarms(context)
        if (!config.optBoolean("notifications") || source(config) == null) return
        val now = System.currentTimeMillis() / 1000
        val windows = snapshot.getJSONObject("windows")
        for ((index, name) in listOf("fiveHour", "weekly").withIndex()) {
            val window = windows.optJSONObject(name) ?: continue
            val reset = SnapshotProtocol.reset(window)
            val label = if (index == 0) "5 小时" else "每周"
            if (snapshot.getLong("expiresAt") > now && (reset == null || reset > now) &&
                100 - window.getDouble("usedPercent") <= config.getInt("threshold")) {
                val id = "$name:${reset ?: "unknown"}"
                if (prefs(context).getString("${name}_low", null) != id && notify(context, 10 + index,
                        "$label 额度提醒", "剩余额度不高于 ${config.getInt("threshold")}%")) {
                    prefs(context).edit().putString("${name}_low", id).apply()
                }
            }
            if (reset != null && reset > now) {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, reset * 1000, alarmIntent(context, index))
            }
        }
    }
    fun notify(context: Context, id: Int, title: String, body: String): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("quota", "额度与重置提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, "quota")
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
        return try { manager.notify(id, notification); true } catch (_: SecurityException) { false }
    }
}
