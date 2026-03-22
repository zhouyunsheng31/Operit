package com.ai.assistance.operit.core.tools.defaultTool.debugger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.SimplifiedUINode
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.UIActionResultData
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.core.tools.UiAutomatorBridge
import com.ai.assistance.operit.core.tools.defaultTool.accessbility.AccessibilityUITools
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.core.tools.system.ShellIdentity
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.OperitPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 调试级别的UI工具，通过内存中的 UiAutomation / UiDevice（Instrumentation）实现 UI 操作。
 * 仅在指定 display 参数时回退到 Shell 命令。
 */
open class DebuggerUITools(context: Context) : AccessibilityUITools(context) {

    companion object {
        private const val TAG = "DebuggerUITools"
    }

    protected open val uiShellIdentity: ShellIdentity? = null

    protected suspend fun executeUiShellCommand(command: String): AndroidShellExecutor.CommandResult {
        return AndroidShellExecutor.executeShellCommand(command, uiShellIdentity)
    }

    private val automation get() = UiAutomatorBridge.uiAutomation
    private val device get() = UiAutomatorBridge.uiDevice

    /**
     * 构建坐标类 shell 命令的 display 参数（如 "-d 1 "）。
     * 仅用于 tap / swipe / longPress 等直接操作坐标的命令；
     * 基于 selector 的操作请使用 By.displayId()。
     */
    private fun getDisplayArg(tool: AITool): String {
        val display = tool.parameters.find { it.name.equals("display", ignoreCase = true) }?.value?.trim()
        return if (!display.isNullOrEmpty()) "-d $display " else ""
    }

    /** 确保自动化服务在线，若未就绪则尝试后台重新拉起 */
    private suspend fun ensureServiceReady(): Boolean {
        if (UiAutomatorBridge.isReady) return true

        AppLogger.i(TAG, "自动化服务未就绪，正在尝试唤醒...")
        val pkgName = context.packageName
        val cmd = "am instrument -w -e class $pkgName.core.tools.AgentInstrumentation $pkgName/.core.tools.AgentInstrumentation"
        executeUiShellCommand("$cmd &")
        for (i in 0..10) {
            if (UiAutomatorBridge.isReady) {
                AppLogger.i(TAG, "自动化服务唤醒成功")
                return true
            }
            delay(500)
        }

        AppLogger.e(TAG, "自动化服务唤醒超时")
        return false
    }

    private fun errorResult(tool: AITool, msg: String): ToolResult {
        return ToolResult(tool.name, false, StringResultData(""), msg)
    }

    /** 获取当前页面 UI 信息：通过内存直读节点树，辅以 dumpsys 获取顶层 Activity 名称 */
    override suspend fun getPageInfo(tool: AITool): ToolResult {
        if (!ensureServiceReady()) return errorResult(tool, "自动化服务未就绪，无法获取 UI 树")

        val displayIdStr = tool.parameters.find { it.name.equals("display", ignoreCase = true) }?.value?.trim()

        return try {
            val windows = automation?.windows
            val rootNode = if (!displayIdStr.isNullOrEmpty() && Build.VERSION.SDK_INT >= 30) {
                val dId = displayIdStr.toIntOrNull() ?: 0
                windows?.find { it.displayId == dId }?.root ?: automation?.rootInActiveWindow
            } else {
                automation?.rootInActiveWindow
            }

            // 手动回收 AccessibilityWindowInfo 以防泄漏
            windows?.forEach { it.recycle() }

            if (rootNode == null) {
                return errorResult(tool, "无法获取活动窗口根节点")
            }

            val simplifiedLayout = convertNodeToSimplified(rootNode)
            var currentPackage = rootNode.packageName?.toString() ?: "Unknown"

            // 读取完毕后释放根节点树，避免 OOM
            rootNode.recycle()

            // 通过 Shell 获取顶层 Activity 名称
            val focusInfo = getFocusInfo()
            if (currentPackage == "Unknown" && focusInfo.packageName != null) {
                currentPackage = focusInfo.packageName!!
            }

            ToolResult(
                toolName = tool.name,
                success = true,
                result = UIPageResultData(
                    packageName = currentPackage,
                    activityName = focusInfo.activityName ?: "Unknown",
                    uiElements = simplifiedLayout
                )
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取页面 UI 信息异常", e)
            errorResult(tool, "获取页面 UI 信息失败: ${e.message}")
        }
    }

    private data class FocusInfoShell(var packageName: String? = null, var activityName: String? = null)

    private suspend fun getFocusInfo(): FocusInfoShell {
        val result = FocusInfoShell()
        val commands = listOf(
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
            "dumpsys activity top | grep ACTIVITY",
            "dumpsys activity activities | grep -E 'topResumedActivity|topActivity'"
        )
        for (command in commands) {
            val shellResult = executeUiShellCommand(command)
            if (shellResult.success && shellResult.stdout.isNotBlank()) {
                val patterns = listOf(
                    "mCurrentFocus=.*?\\s+([a-zA-Z0-9_.]+)/([^\\s}]+)".toRegex(),
                    "mFocusedApp=.*?ActivityRecord\\{.*?\\s+([a-zA-Z0-9_.]+)/\\.?([^\\s}]+)".toRegex(),
                    "topActivity=ComponentInfo\\{([a-zA-Z0-9_.]+)/\\.?([^}]+)\\}".toRegex()
                )
                for (pattern in patterns) {
                    val match = pattern.find(shellResult.stdout)
                    if (match != null && match.groupValues.size >= 3) {
                        result.packageName = match.groupValues[1]
                        result.activityName = match.groupValues[2]
                        return result
                    }
                }
            }
        }
        return result
    }

    private fun convertNodeToSimplified(node: AccessibilityNodeInfo): SimplifiedUINode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val boundsString = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"

        val children = mutableListOf<SimplifiedUINode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                children.add(convertNodeToSimplified(child))
                child.recycle()
            }
        }

        return SimplifiedUINode(
            className = node.className?.toString()?.substringAfterLast('.'),
            text = node.text?.toString()?.replace("&#10;", "\n"),
            contentDesc = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            bounds = boundsString,
            isClickable = node.isClickable,
            children = children
        )
    }

    // ─────────────────────────── clickElement ───────────────────────────

    /** 点击指定元素（支持按 resourceId、contentDesc、className 及坐标范围定位，可选模糊匹配；支持通过 display 参数指定屏幕） */
    override suspend fun clickElement(tool: AITool): ToolResult {
        if (!ensureServiceReady()) return errorResult(tool, "自动化服务未就绪")

        val resourceId = tool.parameters.find { it.name == "resourceId" }?.value
        val className = tool.parameters.find { it.name == "className" }?.value
        val desc = tool.parameters.find { it.name == "contentDesc" }?.value
        val boundsStr = tool.parameters.find { it.name == "bounds" }?.value
        val displayIdStr = tool.parameters.find { it.name.equals("display", ignoreCase = true) }?.value?.trim()

        val partialMatch = tool.parameters.find { it.name == "partialMatch" }?.value?.toBoolean() ?: false
        val index = tool.parameters.find { it.name == "index" }?.value?.toIntOrNull() ?: 0

        val targetDevice = device ?: return errorResult(tool, "UiDevice 实例未初始化")

        // 优先处理坐标模式
        if (boundsStr != null) {
            extractCenterCoordinates(boundsStr)?.let { (x, y) ->
                return tap(AITool("tap", tool.parameters.filter { it.name != "bounds" } + listOf(ToolParameter("x", x.toString()), ToolParameter("y", y.toString()))))
            } ?: return errorResult(tool, "bounds 格式无效")
        }

        try {
            var selector = if (partialMatch) {
                when {
                    resourceId != null -> By.res(java.util.regex.Pattern.compile(".*${java.util.regex.Pattern.quote(resourceId)}.*"))
                    desc != null -> By.descContains(desc)
                    className != null -> By.clazz(java.util.regex.Pattern.compile(".*${java.util.regex.Pattern.quote(className)}.*"))
                    else -> return errorResult(tool, "缺少定位参数")
                }
            } else {
                when {
                    resourceId != null -> By.res(resourceId)
                    desc != null -> By.desc(desc)
                    className != null -> By.clazz(className)
                    else -> return errorResult(tool, "缺少定位参数")
                }
            }

            // 通过 By.displayId 将查找范围限定到指定屏幕
            val displayId = displayIdStr?.toIntOrNull()
            if (displayId != null) {
                selector = selector.displayId(displayId)
            }

            // 等待目标元素出现，最多 3 秒
            targetDevice.wait(Until.hasObject(selector), 3000)
            val uiObj = targetDevice.findObjects(selector).getOrNull(index)

            if (uiObj != null) {
                uiObj.click()
                return ToolResult(tool.name, true, UIActionResultData("click", "成功点击元素 (index: $index)"))
            } else {
                return errorResult(tool, "等待 3000ms 后未找到目标元素 (index: $index)")
            }
        } catch (e: Exception) {
            return errorResult(tool, "点击操作异常: ${e.message}")
        }
    }

    // ─────────────────────────── tap ───────────────────────────

    override suspend fun tap(tool: AITool): ToolResult {
        if (!ensureServiceReady()) return errorResult(tool, "自动化服务未就绪")
        val x = tool.parameters.find { it.name == "x" }?.value?.toIntOrNull()
        val y = tool.parameters.find { it.name == "y" }?.value?.toIntOrNull()
        if (x == null || y == null) return errorResult(tool, "Missing or invalid coordinates. Both 'x' and 'y' must be valid integers.")

        withContext(Dispatchers.Main) { operationOverlay.showTap(x, y) }
        try {
            val displayArg = getDisplayArg(tool)
            val success = if (displayArg.isNotEmpty()) {
                val command = "input ${displayArg}tap $x $y"
                executeUiShellCommand(command).success
            } else {
                device?.click(x, y) ?: false
            }

            return if (success) {
                ToolResult(tool.name, true, UIActionResultData("tap", "成功点击($x,$y)", Pair(x, y)))
            } else {
                errorResult(tool, "点击坐标 ($x, $y) 执行失败")
            }
        } catch (e: Exception) {
            return errorResult(tool, "tap 操作异常: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { operationOverlay.hide() }
        }
    }

    // ─────────────────────────── swipe ───────────────────────────

    override suspend fun swipe(tool: AITool): ToolResult {
        if (!ensureServiceReady()) return errorResult(tool, "自动化服务未就绪")
        val startX = tool.parameters.find { it.name == "start_x" }?.value?.toIntOrNull()
        val startY = tool.parameters.find { it.name == "start_y" }?.value?.toIntOrNull()
        val endX = tool.parameters.find { it.name == "end_x" }?.value?.toIntOrNull()
        val endY = tool.parameters.find { it.name == "end_y" }?.value?.toIntOrNull()
        val durationMs = tool.parameters.find { it.name == "duration" }?.value?.toIntOrNull() ?: 300

        if (startX == null || startY == null || endX == null || endY == null) {
            return errorResult(tool, "滑动参数不完整，start_x/start_y/end_x/end_y 均为必填项")
        }

        withContext(Dispatchers.Main) { operationOverlay.showSwipe(startX, startY, endX, endY) }
        try {
            val displayArg = getDisplayArg(tool)
            val success = if (displayArg.isNotEmpty()) {
                val command = "input ${displayArg}swipe $startX $startY $endX $endY $durationMs"
                executeUiShellCommand(command).success
            } else {
                // UiDevice 步数估算：约每步 5ms
                val steps = (durationMs / 5).coerceAtLeast(10)
                device?.swipe(startX, startY, endX, endY, steps) ?: false
            }

            return if (success) {
                ToolResult(tool.name, true, UIActionResultData("swipe", "滑动成功: ($startX,$startY) → ($endX,$endY)"))
            } else {
                errorResult(tool, "滑动操作执行失败")
            }
        } catch (e: Exception) {
            return errorResult(tool, "滑动操作异常: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { operationOverlay.hide() }
        }
    }

    // ─────────────────────────── longPress ───────────────────────────

    override suspend fun longPress(tool: AITool): ToolResult {
        if (!ensureServiceReady()) return errorResult(tool, "自动化服务未就绪")
        val x = tool.parameters.find { it.name == "x" }?.value?.toIntOrNull()
        val y = tool.parameters.find { it.name == "y" }?.value?.toIntOrNull()
        if (x == null || y == null) return errorResult(tool, "坐标参数缺失，x 和 y 均为必填项")

        withContext(Dispatchers.Main) { operationOverlay.showTap(x, y) }
        try {
            val displayArg = getDisplayArg(tool)
            val durationMs = 800
            val success = if (displayArg.isNotEmpty()) {
                val command = "input ${displayArg}swipe $x $y $x $y $durationMs"
                executeUiShellCommand(command).success
            } else {
                // 通过原位滑动模拟长按（5ms × 160 步 = 800ms）
                device?.swipe(x, y, x, y, 160) ?: false
            }

            return if (success) {
                ToolResult(tool.name, true, UIActionResultData("long_press", "长按操作成功"))
            } else {
                errorResult(tool, "长按操作执行失败")
            }
        } catch (e: Exception) {
            return errorResult(tool, "长按操作异常: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { operationOverlay.hide() }
        }
    }

    // ─────────────────────────── setInputText ───────────────────────────

    override suspend fun setInputText(tool: AITool): ToolResult {
        if (!ensureServiceReady()) return errorResult(tool, "自动化服务未就绪，无法执行输入")
        val text = tool.parameters.find { it.name == "text" }?.value ?: ""

        val overlay = operationOverlay
        try {
            val displayMetrics = context.resources.displayMetrics
            withContext(Dispatchers.Main) {
                overlay.showTextInput(displayMetrics.widthPixels / 2, displayMetrics.heightPixels / 2, text)
            }

            device?.pressKeyCode(KeyEvent.KEYCODE_CLEAR)
            delay(100)

            if (text.isEmpty()) {
                return ToolResult(tool.name, true, UIActionResultData("textInput", "已清空输入框"))
            }

            withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("operit_input", text))
            }
            delay(100)

            device?.pressKeyCode(KeyEvent.KEYCODE_PASTE)

            return ToolResult(tool.name, true, UIActionResultData("textInput", "已通过粘贴方式完成文本输入"))
        } catch (e: Exception) {
            return errorResult(tool, "文本输入操作异常: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { overlay.hide() }
        }
    }

    // ─────────────────────────── pressKey ───────────────────────────

    override suspend fun pressKey(tool: AITool): ToolResult {
        if (!ensureServiceReady()) return errorResult(tool, "自动化服务未就绪")
        val keyCodeStr = tool.parameters.find { it.name == "key_code" }?.value
            ?: return errorResult(tool, "参数缺失: key_code 为必填项")

        try {
            val parsedCode = keyCodeStr.toIntOrNull() ?: when (keyCodeStr.uppercase().removePrefix("KEYCODE_")) {
                "HOME" -> KeyEvent.KEYCODE_HOME
                "BACK" -> KeyEvent.KEYCODE_BACK
                "DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP
                "DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
                "DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
                "DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "ENTER" -> KeyEvent.KEYCODE_ENTER
                "DEL" -> KeyEvent.KEYCODE_DEL
                "CLEAR" -> KeyEvent.KEYCODE_CLEAR
                "SPACE" -> KeyEvent.KEYCODE_SPACE
                "TAB" -> KeyEvent.KEYCODE_TAB
                "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
                "POWER" -> KeyEvent.KEYCODE_POWER
                "VOLUME_UP" -> KeyEvent.KEYCODE_VOLUME_UP
                "VOLUME_DOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN
                "VOLUME_MUTE" -> KeyEvent.KEYCODE_VOLUME_MUTE
                "MENU" -> KeyEvent.KEYCODE_MENU
                "SEARCH" -> KeyEvent.KEYCODE_SEARCH
                "APP_SWITCH" -> KeyEvent.KEYCODE_APP_SWITCH
                else -> null
            }

            val success = if (parsedCode != null) {
                device?.pressKeyCode(parsedCode) ?: false
            } else {
                executeUiShellCommand("input keyevent $keyCodeStr").success
            }

            return if (success) {
                ToolResult(tool.name, true, UIActionResultData("keyPress", "按键成功: $keyCodeStr"))
            } else {
                errorResult(tool, "按键操作执行失败")
            }
        } catch (e: Exception) {
            return errorResult(tool, "按键操作异常: ${e.message}")
        }
    }

    // ─────────────────────────── 截图（保留 shell screencap） ───────────────────────────

    override suspend fun captureScreenshotToFile(tool: AITool): Pair<String?, Pair<Int, Int>?> {
        return try {
            val screenshotDir = OperitPaths.cleanOnExitDir()
            val shortName = System.currentTimeMillis().toString().takeLast(4)
            val file = File(screenshotDir, "$shortName.png")

            // Debugger 模式下优先尝试 Shell 模式截图
            AppLogger.d(TAG, "captureScreenshotToFile: Attempting shell screencap")
            val command = "screencap -p ${file.absolutePath}"
            val result = executeUiShellCommand(command)

            if (result.success && file.exists()) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                val dimensions = if (options.outWidth > 0 && options.outHeight > 0) {
                    Pair(options.outWidth, options.outHeight)
                } else {
                    null
                }
                AppLogger.d(TAG, "captureScreenshotToFile: Shell screencap success")
                return Pair(file.absolutePath, dimensions)
            }

            // 如果 Shell 失败，回退到无障碍截图
            AppLogger.w(TAG, "captureScreenshotToFile: Shell screencap failed, falling back to accessibility")
            super.captureScreenshotToFile(tool)
        } catch (e: Exception) {
            AppLogger.e(TAG, "captureScreenshotToFile failed in Debugger", e)
            Pair(null, null)
        }
    }

    override suspend fun captureScreenshot(tool: AITool): Pair<String?, Pair<Int, Int>?> {
        return captureScreenshotToFile(tool)
    }

    // ─────────────────────────── 工具方法 ───────────────────────────

    /** 从边界字符串提取中心坐标 返回中心点坐标，或null如果格式无效 */
    protected fun extractCenterCoordinates(bounds: String): Pair<Int, Int>? {
        val boundsPattern = "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]".toRegex()
        val matchResult = boundsPattern.find(bounds) ?: return null
        if (matchResult.groupValues.size < 5) return null

        val x1 = matchResult.groupValues[1].toInt()
        val y1 = matchResult.groupValues[2].toInt()
        val x2 = matchResult.groupValues[3].toInt()
        val y2 = matchResult.groupValues[4].toInt()

        return Pair((x1 + x2) / 2, (y1 + y2) / 2)
    }
}
