package com.dm.labs.wifi.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScanLogManagerTest {

    @Before
    fun setUp() {
        ScanLogManager.clearLogs()
    }

    @After
    fun tearDown() {
        ScanLogManager.clearLogs()
    }

    @Test
    fun `log adds entry to list`() {
        ScanLogManager.log("First message")

        val logs = ScanLogManager.logs.value
        assertEquals(1, logs.size)
        assertEquals("First message", logs[0].message)
    }

    @Test
    fun `newest log appears first`() {
        ScanLogManager.log("Older")
        ScanLogManager.log("Newer")

        val logs = ScanLogManager.logs.value
        assertEquals(2, logs.size)
        assertEquals("Newer", logs[0].message)
        assertEquals("Older", logs[1].message)
    }

    @Test
    fun `clearLogs removes all entries`() {
        ScanLogManager.log("One")
        ScanLogManager.log("Two")
        ScanLogManager.log("Three")

        ScanLogManager.clearLogs()

        assertTrue(ScanLogManager.logs.value.isEmpty())
    }

    @Test
    fun `log entry has timestamp`() {
        val before = System.currentTimeMillis()
        ScanLogManager.log("Timestamped")
        val after = System.currentTimeMillis()

        val log = ScanLogManager.logs.value.first()
        assertTrue(log.timestamp in before..after)
    }

    @Test
    fun `multiple logs preserve all entries`() {
        repeat(50) { i ->
            ScanLogManager.log("Log $i")
        }

        assertEquals(50, ScanLogManager.logs.value.size)
        assertEquals("Log 49", ScanLogManager.logs.value.first().message)
        assertEquals("Log 0", ScanLogManager.logs.value.last().message)
    }
}

