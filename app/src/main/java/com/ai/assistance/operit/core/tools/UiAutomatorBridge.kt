package com.ai.assistance.operit.core.tools

import android.app.UiAutomation
import androidx.test.uiautomator.UiDevice

/**
 * A singleton bridge holding the high-privileged UiAutomation and UiDevice instances
 * injected by AgentInstrumentation. Provides an instant memory-level gateway for UI tools.
 */
object UiAutomatorBridge {
    @Volatile
    var uiAutomation: UiAutomation? = null
    
    @Volatile
    var uiDevice: UiDevice? = null

    @Volatile
    var requestStop: Boolean = false

    val isReady: Boolean
        get() = uiAutomation != null && uiDevice != null
}
