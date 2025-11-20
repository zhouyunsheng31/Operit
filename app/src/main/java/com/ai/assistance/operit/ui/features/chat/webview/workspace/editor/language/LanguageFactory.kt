package com.ai.assistance.operit.ui.features.chat.webview.workspace.editor.language

import android.util.Log

/**
 * 语言支持工厂
 * Monaco Editor通过WebView加载，语言支持由Monaco自动处理
 */
object LanguageFactory {
    private const val TAG = "LanguageFactory"
    private var initialized = false

    /**
     * 初始化语言工厂
     * Monaco Editor使用时不需要额外的初始化
     */
    fun init() {
        if (initialized) {
            Log.d(TAG, "LanguageFactory already initialized")
            return
        }

        try {
            Log.d(TAG, "LanguageFactory initialized (Monaco Editor handles language support)")
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LanguageFactory", e)
        }
    }

    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean = initialized
}
