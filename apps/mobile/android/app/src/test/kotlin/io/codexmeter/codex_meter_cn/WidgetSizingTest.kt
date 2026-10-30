package io.codexmeter.codex_meter_cn

import org.junit.Assert.*
import org.junit.Test

class WidgetSizingTest {
    @Test fun compactOnlyFiveHourAndLargerLayoutsKeepBoth() {
        assertEquals(1,WidgetSizing.tier(180,82))
        assertEquals(2,WidgetSizing.tier(180,180))
        assertEquals(3,WidgetSizing.tier(180,280))
        assertEquals(1,WidgetSizing.tier(120,280))
    }
}
