package com.hearthassist.core

import com.hearthassist.core.log.LogConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogConfigTest {
    @Test
    fun createsConfigFromNothing() {
        val config = LogConfig.merge(null)
        assertTrue(config.contains("[Power]\nLogLevel=1\nFilePrinting=true\nConsolePrinting=false\nScreenPrinting=false\nVerbose=true\n"))
        assertTrue(config.contains("[Arena]"))
        assertTrue(LogConfig.isComplete(config))
        assertFalse(LogConfig.isComplete(null))
    }

    @Test
    fun keepsOtherSections() {
        val existing = "[Achievements]\r\nLogLevel=1\r\nFilePrinting=true\r\n[Power]\r\nLogLevel=1\r\nFilePrinting=false\r\n"
        assertFalse(LogConfig.isComplete(existing))
        val merged = LogConfig.merge(existing)
        assertTrue(merged.startsWith("[Achievements]\nLogLevel=1\nFilePrinting=true\n"))
        assertTrue(merged.contains("[Power]\nLogLevel=1\nFilePrinting=true"))
        assertEquals(merged, LogConfig.merge(merged))
        assertTrue(LogConfig.isComplete(merged))
    }
}
