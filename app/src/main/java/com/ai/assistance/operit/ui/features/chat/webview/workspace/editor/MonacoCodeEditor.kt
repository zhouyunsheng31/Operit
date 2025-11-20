package com.ai.assistance.operit.ui.features.chat.webview.workspace.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Monaco Editor封装 - 基于WebView的专业代码编辑器（VSCode同款）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MonacoCodeEditor(
    code: String,
    language: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    editorRef: (NativeCodeEditor) -> Unit,
    readOnly: Boolean = false,
    theme: String = "vs-dark",
    fontSize: Int = 14,
    wordWrap: Boolean = false
) {
    val context = LocalContext.current
    // 记录当前加载的文件内容哈希或ID，用于判断是否切换了文件
    // 简单的做法是使用 code 的 hashCode 或者引入 fileId 参数
    // 这里我们使用一个引用来判断是否是同一个内容源
    val currentCodeRef = rememberUpdatedState(code)
    
    // 内部持有的 WebView 引用
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isEditorReady by remember { mutableStateOf(false) }
    
    // 监听代码变化（外部强制更新）
    LaunchedEffect(code) {
        if (isEditorReady && webView != null) {
            // 获取当前编辑器内容进行比较，或者使用更高效的各种标记位
            // 这里我们使用一个简单的策略：
            // 如果正在输入（由 onCodeChange 触发），由于 Compose 的重组机制，
            // code 会更新为新值。我们需要区分这个新值是"输入产生的"还是"外部传入的"。
            
            // 但更简单的做法是：信任 WebView 中的内容是最新且正确的。
            // 只有当我们需要"重置"或"切换文件"时，才调用 setValue。
            // 为了实现这一点，我们可以在 JS 端通过 AndroidBridge 告知当前内容的 hash 或 version。
            
            // 现有方案优化：
            // 直接调用 JS 检查内容是否一致，如果不一致才更新。
            // 并且由 JS 端判断是否需要保留光标位置。
            val jsonCode = JSONObject.quote(code)
            webView?.evaluateJavascript("""
                if (window.monacoAPI) {
                    var current = window.monacoAPI.getValue();
                    if (current !== $jsonCode) {
                        // 内容不一致，说明是外部变更（如切换文件、AI生成代码、格式化）
                        // 此时才需要更新编辑器内容
                        window.monacoAPI.setValue($jsonCode);
                    }
                }
            """.trimIndent(), null)
        }
    }

    // 监听其他属性变化
    LaunchedEffect(language) {
        if (isEditorReady) {
            webView?.evaluateJavascript("window.monacoAPI?.setLanguage('$language');", null)
        }
    }
    
    LaunchedEffect(theme) {
        if (isEditorReady) {
            webView?.evaluateJavascript("window.monacoAPI?.setTheme('$theme');", null)
        }
    }
    
    LaunchedEffect(readOnly) {
        if (isEditorReady) {
            webView?.evaluateJavascript("window.monacoAPI?.setReadOnly($readOnly);", null)
        }
    }
    
    LaunchedEffect(fontSize) {
        if (isEditorReady) {
            webView?.evaluateJavascript("window.monacoAPI?.setFontSize($fontSize);", null)
        }
    }
    
    LaunchedEffect(wordWrap) {
        if (isEditorReady) {
            webView?.evaluateJavascript("window.monacoAPI?.setWordWrap($wordWrap);", null)
        }
    }
    
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createMonacoWebView(
                ctx,
                code,
                language,
                readOnly,
                theme,
                fontSize,
                wordWrap,
                onCodeChange = { newCode ->
                    // 编辑器内容变化，直接通知上层
                    // 不需要更新本地状态来触发重组，因为内容源就在编辑器里
                    onCodeChange(newCode)
                },
                onEditorReady = {
                    Log.d("MonacoCodeEditor", "Monaco Editor加载完成")
                    isEditorReady = true
                }
            ).also { 
                webView = it
                val editorInterface = MonacoEditorInterface(it)
                editorRef(editorInterface)
            }
        },
        // update 块留空或只做最小必要工作，主要逻辑移至 LaunchedEffect
        update = { 
            // 空实现，依赖 LaunchedEffect 处理更新
        }
    )
}

/**
 * 创建Monaco WebView实例
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createMonacoWebView(
    context: Context,
    initialCode: String,
    language: String,
    readOnly: Boolean,
    theme: String,
    fontSize: Int,
    wordWrap: Boolean,
    onCodeChange: (String) -> Unit,
    onEditorReady: () -> Unit
): WebView {
    return WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100
        }
        
        setBackgroundColor(Color.parseColor("#1e1e1e"))
        
        // 设置布局参数为MATCH_PARENT
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        
        // 设置WebView可以获取焦点
        isFocusable = true
        isFocusableInTouchMode = true
        
        // 处理触摸事件，阻止父级拦截（参考Terminal实现）
        var startX = 0f
        var startY = 0f
        var startTime = 0L
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        // 自定义点击判定阈值，稍微放大一点，防止太灵敏
        // 但主要靠时间来过滤滑动
        val clickThreshold = 20f * context.resources.displayMetrics.density 
        
        setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    startTime = System.currentTimeMillis()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.x - startX)
                    val diffY = Math.abs(event.y - startY)
                    val duration = System.currentTimeMillis() - startTime
                    
                    // 严格的点击判定：
                    // 1. 移动距离很小（防止滑动误判）
                    // 2. 按下时间很短（防止长按或慢速拖拽误判，通常点击在200ms内）
                    if (diffX < clickThreshold && diffY < clickThreshold && duration < 250) {
                        v.requestFocus()
                        
                        // 如果没有弹出键盘，可以尝试强制显示
                        // val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        // imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // 返回false让View继续处理事件
        }
        
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // 等待Monaco加载完成后初始化
                view?.postDelayed({
                    try {
                        val jsonCode = JSONObject.quote(initialCode)
                        view.evaluateJavascript("""
                            if (window.monacoAPI) {
                                window.monacoAPI.setValue($jsonCode);
                                window.monacoAPI.setLanguage('$language');
                                window.monacoAPI.setTheme('$theme');
                                window.monacoAPI.setReadOnly($readOnly);
                                window.monacoAPI.setFontSize($fontSize);
                                window.monacoAPI.setWordWrap($wordWrap);
                                window.monacoAPI.focus();
                            }
                        """.trimIndent(), null)
                    } catch (e: Exception) {
                        Log.e("MonacoCodeEditor", "初始化失败", e)
                    }
                }, 800) // 增加延迟以确保Monaco完全加载
            }
        }
        
        // JavaScript Bridge
        addJavascriptInterface(object {
            @JavascriptInterface
            fun onContentChanged(content: String) {
                onCodeChange(content)
            }
            
            @JavascriptInterface
            fun onEditorReady() {
                onEditorReady()
            }
        }, "AndroidBridge")
        
        // 加载Monaco Editor HTML
        loadUrl("file:///android_asset/monaco_editor.html")
    }
}

/**
 * Monaco Editor接口实现
 */
class MonacoEditorInterface(private val webView: WebView) : NativeCodeEditor {
    
    private fun execJS(script: String) {
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }
    
    override fun undo() {
        execJS("window.monacoAPI?.undo();")
    }
    
    override fun redo() {
        execJS("window.monacoAPI?.redo();")
    }
    
    override fun replaceAllText(text: String) {
        val jsonText = JSONObject.quote(text)
        execJS("window.monacoAPI?.setValue($jsonText);")
    }
    
    override fun insertText(text: String) {
        val jsonText = JSONObject.quote(text)
        execJS("window.monacoAPI?.insertText($jsonText);")
    }
    
    override fun getSelectedText(): String? {
        // 异步操作，这里返回null
        // 实际使用时可以通过回调实现
        return null
    }
    
    /**
     * 设置焦点
     */
    fun focus() {
        execJS("window.monacoAPI?.focus();")
    }
    
    /**
     * 设置主题
     */
    fun setTheme(theme: String) {
        execJS("window.monacoAPI?.setTheme('$theme');")
    }
    
    /**
     * 设置只读
     */
    fun setReadOnly(readOnly: Boolean) {
        execJS("window.monacoAPI?.setReadOnly($readOnly);")
    }
    
    /**
     * 触发布局更新
     */
    fun layout() {
        execJS("window.monacoAPI?.layout();")
    }
}
