package com.ai.assistance.operit.core.tools

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.uiautomator.UiDevice
import com.ai.assistance.operit.util.AppLogger

/**
 * Instrumentation process that is spawned via root 'am instrument'. 
 * It runs in the app's process but on a separate thread, injecting UiAutomation 
 * into the global bridge to bypass ADB shell latency.
 */
class AgentInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // start() spawns a new thread and calls onStart()
        start() 
    }

    override fun onStart() {
        super.onStart()
        AppLogger.d("AgentInstrumentation", "自动化守护进程已启动！")

        UiAutomatorBridge.uiAutomation = uiAutomation
        
        // Ensure that context is available or passing instrumentation directly
        UiAutomatorBridge.uiDevice = UiDevice.getInstance(this)

        AppLogger.i("AgentInstrumentation", "桥接初始化完成, isReady: ${UiAutomatorBridge.isReady}")

        // 守护进程保活 (阻塞当前 Instrumentation 线程，不影响主应用 UI)
        try {
            while (!UiAutomatorBridge.requestStop) {
                Thread.sleep(1000)
            }
            AppLogger.d("AgentInstrumentation", "收到终止指令，正常退出...")
            finish(android.app.Activity.RESULT_OK, Bundle())
        } catch (e: InterruptedException) {
            AppLogger.e("AgentInstrumentation", "守护进程被中断", e)
        } finally {
            UiAutomatorBridge.uiAutomation = null
            UiAutomatorBridge.uiDevice = null
            UiAutomatorBridge.requestStop = false
            AppLogger.d("AgentInstrumentation", "守护进程彻底退出")
        }
    }
}
