package com.goldsky.ssp.payment

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceAccessManagerTest {

    @Before
    @After
    fun reset() {
        // DeviceAccessManager is a process-wide singleton; state must not leak
        // between tests. init() is never called here (no Context in plain
        // JUnit), which is fine -- prefs() safely no-ops when appContext is
        // null, so these setters only exercise the in-memory state machine.
        DeviceAccessManager.setRemoteLock(false)
        DeviceAccessManager.applyActiveState(true)
    }

    @Test
    fun `starts unlocked`() {
        assertFalse(DeviceAccessManager.isLocked())
        assertEquals(null, DeviceAccessManager.lockReason())
    }

    @Test
    fun `remote lock alone locks the terminal`() {
        DeviceAccessManager.setRemoteLock(true)
        assertTrue(DeviceAccessManager.isLocked())
        assertEquals("Remote locked by operator", DeviceAccessManager.lockReason())
    }

    @Test
    fun `device disabled alone locks the terminal`() {
        DeviceAccessManager.applyActiveState(false)
        assertTrue(DeviceAccessManager.isLocked())
        assertEquals("Device disabled by admin", DeviceAccessManager.lockReason())
    }

    @Test
    fun `both remote lock and device disabled report the combined reason`() {
        DeviceAccessManager.setRemoteLock(true)
        DeviceAccessManager.applyActiveState(false)
        assertTrue(DeviceAccessManager.isLocked())
        assertEquals("Remote locked & disabled by admin", DeviceAccessManager.lockReason())
    }

    @Test
    fun `applyActiveState(null) leaves current state untouched`() {
        // Documented contract: null means "unknown" (network failure / RPC
        // error), never "locked" -- a connectivity blip must not false-positive
        // lock out a legitimate device.
        DeviceAccessManager.applyActiveState(false)
        assertTrue(DeviceAccessManager.isLocked())

        DeviceAccessManager.applyActiveState(null)

        assertTrue("a null result must not clear an existing lock either", DeviceAccessManager.isLocked())
        assertEquals("Device disabled by admin", DeviceAccessManager.lockReason())
    }

    @Test
    fun `applyActiveState(null) does not spuriously lock an active device`() {
        DeviceAccessManager.applyActiveState(null)
        assertFalse(DeviceAccessManager.isLocked())
    }

    @Test
    fun `clearing remote lock unlocks an otherwise-active device`() {
        DeviceAccessManager.setRemoteLock(true)
        assertTrue(DeviceAccessManager.isLocked())

        DeviceAccessManager.setRemoteLock(false)

        assertFalse(DeviceAccessManager.isLocked())
        assertEquals(null, DeviceAccessManager.lockReason())
    }

    @Test
    fun `re-enabling the device unlocks it once remote lock is also clear`() {
        DeviceAccessManager.applyActiveState(false)
        assertTrue(DeviceAccessManager.isLocked())

        DeviceAccessManager.applyActiveState(true)

        assertFalse(DeviceAccessManager.isLocked())
    }
}
