package com.yufish.autocount

import android.content.Intent
import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSmokeTest {
    @Test fun nativeScreenshotChineseOcrAndPythonEngineLoadTogether() {
        assertTrue("Only run on a local Android emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Instrumentation restarts the app process. Rebind the emulator-only test service.
        for (command in listOf(
            "settings put secure enabled_accessibility_services null",
            "settings put secure enabled_accessibility_services com.yufish.autocount/com.yufish.autocount.AutomationService",
            "settings put secure accessibility_enabled 1"
        )) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = System.currentTimeMillis() + 90000
        while (AutomationService.instance == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val service = requireNotNull(AutomationService.instance) { "Enable the accessibility service on the local emulator first." }
        assertTrue(service.start("selftest", false, false))
        while (service.running() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertFalse("Runtime smoke test timed out", service.running())
        val result = JSONObject(service.lastResult())
        assertEquals(result.toString(), "checked", result.getString("status"))
        assertTrue(service.logText().contains("self_test_passed"))
    }
}
