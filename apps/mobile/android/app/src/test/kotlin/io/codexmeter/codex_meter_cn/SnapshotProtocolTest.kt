package io.codexmeter.codex_meter_cn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnapshotProtocolTest {
    private val raw = """{"schemaVersion":1,"collectedAt":1000,"expiresAt":1900,"windows":{"fiveHour":{"usedPercent":37,"windowMinutes":300,"resetsAt":2000},"weekly":null}}"""
    @Test fun validUnknownWindowAndCountdown() {
        val snapshot = SnapshotProtocol.parse(raw, 1000)
        assertTrue(snapshot.getJSONObject("windows").isNull("weekly"))
        val window = snapshot.getJSONObject("windows").getJSONObject("fiveHour")
        assertEquals("0时16分", SnapshotProtocol.countdown(window, 1000))
        assertEquals("待刷新确认重置", SnapshotProtocol.countdown(window, 2000))
    }
    @Test fun rejectsSecretsAndInvalidWindows() {
        for (bad in listOf(raw.replace("37", "101"), raw.replace("300", "15"), raw.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            JSONObject(raw).put("accessToken", "secret").toString())) {
            assertThrows(IllegalArgumentException::class.java) { SnapshotProtocol.parse(bad, 1000) }
        }
    }
    @Test fun endpointRules() {
        SnapshotProtocol.endpoint("https://host/v1/snapshot")
        SnapshotProtocol.endpoint("http://127.0.0.1:8788/v1/snapshot")
        for (bad in listOf("http://public.example/v1/snapshot", "https://u:p@host/v1/snapshot", "https://host/v1/snapshot?token=x")) {
            assertThrows(IllegalArgumentException::class.java) { SnapshotProtocol.endpoint(bad) }
        }
    }
}
