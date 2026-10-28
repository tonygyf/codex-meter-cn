package io.codexmeter.codex_meter_cn

import org.junit.Assert.*
import org.junit.Test

class QuotaTileSummaryTest {
    private fun snapshot() = SnapshotProtocol.parse("""{"schemaVersion":1,"collectedAt":1000,"expiresAt":1900,"windows":{"fiveHour":{"usedPercent":37,"windowMinutes":300,"resetsAt":1800},"weekly":null}}""", 1000)
    @Test fun displaysRemainingAndUnknownWindows() {
        assertEquals("5h 63% · 周 —" to true, QuotaTileSummary.text(snapshot(), 1000, false))
        assertEquals("请先连接数据源" to false, QuotaTileSummary.text(null, 1000, false))
    }
    @Test fun neverInfersRecoveryAfterResetOrNetworkFailure() {
        for ((now, offline) in listOf(1800L to false, 2000L to false, 1000L to true)) {
            assertEquals("上次 5h 63% · 周 —" to false, QuotaTileSummary.text(snapshot(), now, offline))
        }
    }
}
