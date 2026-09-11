package com.lukesteuber.signalstation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Explicit emulator check with the installed TalkBack service; restores its original setting. */
class SignalTalkBackTest {
    @Test fun talkBackCanFocusAndActivateMainDestinations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("talkback_smoke") == "1")
        assumeTrue(Build.HARDWARE.contains("ranchu"))
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val previousServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val service = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        val manager = context.getSystemService(AccessibilityManager::class.java)
        fun shell(command: String) { automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        fun waitFor(check: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!check()) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    val pending = ArrayDeque<AccessibilityNodeInfo>()
                    automation.rootInActiveWindow?.let(pending::add)
                    var remaining = 80
                    while (pending.isNotEmpty() && remaining-- > 0) {
                        val node = pending.removeFirst()
                        println("ACCESSIBILITY_NODE package=${node.packageName} text=${node.text?.take(100)} description=${node.contentDescription?.take(100)} visible=${node.isVisibleToUser} clickable=${node.isClickable}")
                        repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
                    }
                    fail("Accessibility state did not settle.")
                }
                Thread.sleep(100)
            }
        }
        fun visibleText(label: String): Boolean {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::add)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.isVisibleToUser && node.text?.toString() == label) return true
                repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
            }
            return false
        }
        fun clickable(label: String): AccessibilityNodeInfo? {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::add)
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) candidates += node
                repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
            }
            return candidates.firstNotNullOfOrNull { candidate ->
                var node: AccessibilityNodeInfo? = candidate
                repeat(12) {
                    if (node?.isVisibleToUser == true && node?.isClickable == true && node?.isEnabled == true) return@firstNotNullOfOrNull node
                    node = node?.parent
                }
                null
            }
        }
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            val selected = (previousServices.orEmpty().split(':').filter { it.isNotBlank() } + service).distinct().joinToString(":")
            require(selected.matches(Regex("[A-Za-z0-9._/:]+")))
            shell("settings put secure enabled_accessibility_services $selected")
            shell("settings put secure accessibility_enabled 1")
            waitFor { manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val station = (context.applicationContext as SignalApplication).station
                waitFor { station.state.value.initialized && !station.state.value.busy }
                scenario.onActivity { station.updateSettings(station.state.value.settings.copy(onboardingComplete = true)) }
                waitFor { station.state.value.settings.onboardingComplete && !station.state.value.busy }
                waitFor {
                    // A fresh emulator may ask about TalkBack's own notifications.
                    // Declining those is independent of the tested app's sources.
                    if (automation.rootInActiveWindow?.packageName?.toString()?.endsWith("permissioncontroller") == true)
                        clickable("Don’t allow")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickable("Ask") != null
                }
                for (label in listOf("Ask", "History", "Now")) {
                    waitFor { clickable(label) != null }
                    val node = clickable(label)!!
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), "$label can receive screen-reader focus")
                    waitFor { node.refresh() && node.isAccessibilityFocused }
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "$label can be activated through accessibility")
                    val expected = when (label) {
                        "Ask" -> "Ask about your observations"
                        "History" -> "Recordings"
                        else -> "Right now"
                    }
                    waitFor { visibleText(expected) }
                }
                for (label in listOf("Around me", "Places", "My devices", "Signals", "Back")) {
                    waitFor { clickable(label) != null }
                    val node = clickable(label)!!
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), "$label receives focus")
                    waitFor { node.refresh() && node.isAccessibilityFocused }
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "$label activates")
                }
                waitFor { visibleText("Right now") }
                assertFalse(station.state.value.live.running, "Visiting panes does not start collection")
                println("TALKBACK_NAVIGATION serviceBound=true destinations=Now,Ask,History,AroundMe,Places,MyDevices,Signals focusAndActivation=passed")
            }
        } finally {
            if (previousServices.isNullOrEmpty()) shell("settings delete secure enabled_accessibility_services")
            else {
                require(previousServices.matches(Regex("[A-Za-z0-9._/:]+")))
                shell("settings put secure enabled_accessibility_services $previousServices")
            }
            shell("settings put secure accessibility_enabled $previousEnabled")
        }
    }
}
