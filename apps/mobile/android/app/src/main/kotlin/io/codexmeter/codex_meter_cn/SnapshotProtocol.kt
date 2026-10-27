package io.codexmeter.codex_meter_cn

import org.json.JSONObject
import java.net.URI

object SnapshotProtocol {
    private fun exactKeys(value: JSONObject, keys: Set<String>) = value.keys().asSequence().toSet() == keys
    private fun integer(value: Any?): Long {
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it > 0) }
    }
    fun parse(raw: String, now: Long = System.currentTimeMillis() / 1000): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= 16384)
        val value = JSONObject(raw)
        require(exactKeys(value, setOf("schemaVersion", "collectedAt", "expiresAt", "windows")))
        require(integer(value.get("schemaVersion")) == 1L)
        val collected = integer(value.get("collectedAt"))
        val expires = integer(value.get("expiresAt"))
        require(collected <= now + 60 && expires > collected && expires - collected <= 3600)
        val windows = value.getJSONObject("windows")
        require(exactKeys(windows, setOf("fiveHour", "weekly")))
        for ((name, minutes) in listOf("fiveHour" to 300L, "weekly" to 10080L)) {
            if (windows.isNull(name)) continue
            val window = windows.getJSONObject(name)
            require(exactKeys(window, setOf("usedPercent", "windowMinutes", "resetsAt")))
            val used = window.get("usedPercent")
            require(used is Number && used.toDouble().isFinite() && used.toDouble() in 0.0..100.0)
            require(integer(window.get("windowMinutes")) == minutes)
            if (!window.isNull("resetsAt")) integer(window.get("resetsAt"))
        }
        return value
    }
    fun endpoint(raw: String): URI = URI(raw).also {
        require(it.userInfo == null && it.rawQuery == null && it.rawFragment == null)
        require(it.path == "/v1/snapshot" && !it.host.isNullOrBlank())
        require(it.scheme == "https" || it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2"))
    }
    fun reset(window: JSONObject?): Long? = window?.let { if (it.isNull("resetsAt")) null else it.getLong("resetsAt") }
    fun countdown(window: JSONObject?, now: Long): String {
        val reset = reset(window) ?: return "重置未知"
        val seconds = reset - now
        if (seconds <= 0) return "待刷新确认重置"
        return if (seconds >= 86400) "${seconds / 86400}天${seconds / 3600 % 24}时" else "${seconds / 3600}时${seconds / 60 % 60}分"
    }
}
