package com.antiscam.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureStoreTest {
    @Before
    fun resetState() {
        // These tests use only the memory store. They never initialize Android preferences.
        CaptureStore.reset()
    }

    @Test
    fun monitoringCannotStartWithoutConsent() {
        CaptureStore.setEnabled(true)

        assertFalse(CaptureStore.state.value.consentGranted)
        assertFalse(CaptureStore.state.value.monitoringEnabled)
    }

    @Test
    fun withdrawingConsentStopsCapturePurgesTextAndInvalidatesPendingWork() {
        CaptureStore.setConsent(true)
        CaptureStore.setEnabled(true)
        CaptureStore.result("Fixture result", 1234L)
        val before = CaptureStore.state.value

        CaptureStore.setConsent(false)

        val after = CaptureStore.state.value
        assertFalse(after.consentGranted)
        assertFalse(after.monitoringEnabled)
        assertEquals("", after.latestText)
        assertNull(after.lastCapturedAtMillis)
        assertEquals(0, after.captureCount)
        assertTrue(after.revision > before.revision)
    }

    @Test
    fun clearingResultInvalidatesPendingWorkWithoutChangingConsentOrMonitoring() {
        CaptureStore.setConsent(true)
        CaptureStore.setEnabled(true)
        CaptureStore.result("Fixture result", 1234L)
        val revision = CaptureStore.state.value.revision

        CaptureStore.clearResult()

        val after = CaptureStore.state.value
        assertEquals("", after.latestText)
        assertNull(after.lastCapturedAtMillis)
        assertTrue(after.consentGranted)
        assertTrue(after.monitoringEnabled)
        assertTrue(after.revision > revision)
    }

    @Test
    fun pausingInvalidatesPendingWorkAndKeepsTheLastResultForInspection() {
        CaptureStore.setConsent(true)
        CaptureStore.setEnabled(true)
        CaptureStore.result("Fixture result", 1234L)
        val revision = CaptureStore.state.value.revision

        CaptureStore.setEnabled(false)

        val after = CaptureStore.state.value
        assertFalse(after.monitoringEnabled)
        assertEquals("Fixture result", after.latestText)
        assertTrue(after.revision > revision)
    }

}
