package io.codexmeter.codex_meter_cn

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection

class MeterRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result { MeterSync.refresh(applicationContext); return Result.success() }
}

object MeterSync {
    @Synchronized fun refresh(context: Context): Boolean {
        val applicationContext = context.applicationContext
        MeterStore.prefs(applicationContext).edit().putLong("lastAttempt", System.currentTimeMillis()/1000).apply()
        var attemptedSource: Pair<String,String>? = null
        var connection: HttpURLConnection? = null
        try {
            val config = MeterStore.config(applicationContext) ?: return false
            val source = MeterStore.source(config) ?: return false
            attemptedSource=source
            val (url, token) = source
            require(token.length in 32..1000)
            connection = SnapshotProtocol.endpoint(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("User-Agent", "CodexMeterCN/0.1 AndroidBackground")
            if (connection.responseCode != 200) throw IllegalStateException("fetch_failed")
            val bytes = connection.inputStream.use { it.readBytesBounded() }
            MeterStore.accept(applicationContext, String(bytes, Charsets.UTF_8), source)
            MeterStore.prefs(applicationContext).edit().putLong("lastAttempt", System.currentTimeMillis() / 1000).apply()
            return true
        } catch (_: Exception) {
            val currentSource=try {MeterStore.config(applicationContext)?.let{MeterStore.source(it)}}catch(_:Exception){null}
            if(currentSource!=attemptedSource)return false
            MeterStore.prefs(applicationContext).edit().putBoolean("lastError", true).apply()
            MeterWidget.updateAll(applicationContext)
            MeterTileService.update(applicationContext)
            // A periodic run will try again. Do not spin on invalid credentials.
            MeterStore.prefs(applicationContext).edit().putLong("lastAttempt", System.currentTimeMillis() / 1000).apply()
            return false
        } finally { connection?.disconnect() }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            require(output.size() + count <= 16384)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
