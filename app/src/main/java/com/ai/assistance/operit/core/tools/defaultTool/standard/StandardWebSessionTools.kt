package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.app.DownloadManager
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionBrowserHost
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionBrowserState
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionBrowserTab
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionHistoryStore
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionPermissionRequestCoordinator
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionSessionHistoryItem
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class StandardWebSessionTools(private val context: Context) : ToolExecutor {

    companion object {
        private const val TAG = "WebSessionTools"
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val mainHandler = Handler(Looper.getMainLooper())

        private val sessions = ConcurrentHashMap<String, WebSession>()
        private val sessionOrder = mutableListOf<String>()

        private val sessionOrderLock = Any()
        private val overlayLock = Any()
        private val sessionConfigLock = Any()

        @Volatile private var browserHost: WebSessionBrowserHost? = null
        @Volatile private var activeSessionId: String? = null
        @Volatile private var desktopModeEnabled: Boolean = true
        @Volatile private var desktopModeInitialized: Boolean = false
    }

    private val historyStore by lazy { WebSessionHistoryStore.getInstance(context.applicationContext) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureDesktopModeInitialized()
    }

    private data class WebSession(
        val id: String,
        val webView: WebView,
        val sessionName: String?,
        val customUserAgent: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        @Volatile var currentUrl: String = "about:blank"
        @Volatile var pageTitle: String = ""
        @Volatile var pageLoaded: Boolean = false
        @Volatile var isLoading: Boolean = false
        @Volatile var canGoBack: Boolean = false
        @Volatile var canGoForward: Boolean = false
        @Volatile var hasSslError: Boolean = false
        @Volatile var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
        @Volatile var lastFileChooserRequestAt: Long = 0L
        @Volatile var lastDownloadEvent: WebDownloadEvent? = null
        @Volatile var lastDownloadEventAt: Long = 0L
    }

    private data class WebDownloadEvent(
        val status: String,
        val type: String,
        val fileName: String,
        val url: String? = null,
        val mimeType: String? = null,
        val savedPath: String? = null,
        val downloadId: Long? = null,
        val error: String? = null
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("status", status)
                .put("type", type)
                .put("file_name", fileName)
                .also { json ->
                    if (!url.isNullOrBlank()) {
                        json.put("url", url)
                    }
                    if (!mimeType.isNullOrBlank()) {
                        json.put("mime_type", mimeType)
                    }
                    if (!savedPath.isNullOrBlank()) {
                        json.put("saved_path", savedPath)
                    }
                    if (downloadId != null) {
                        json.put("download_id", downloadId)
                    }
                    if (!error.isNullOrBlank()) {
                        json.put("error", error)
                    }
                }
    }

    private inner class WebDownloadBridge(private val session: WebSession) {
        @JavascriptInterface
        fun downloadBase64(base64Data: String?, fileName: String?, mimeType: String?) {
            handleInlineDownload(
                session = session,
                base64Data = base64Data.orEmpty(),
                fileName = fileName.orEmpty(),
                mimeType = mimeType.orEmpty(),
                type = "inline"
            )
        }

        @JavascriptInterface
        fun log(message: String?) {
            AppLogger.d(TAG, "web download bridge: ${message.orEmpty()}")
        }
    }

    override fun invoke(tool: AITool): ToolResult {
        return try {
            when (tool.name) {
                "start_web" -> startWeb(tool)
                "stop_web" -> stopWeb(tool)
                "web_navigate" -> webNavigate(tool)
                "web_eval" -> webEval(tool)
                "web_click" -> webClick(tool)
                "web_fill" -> webFill(tool)
                "web_file_upload" -> webFileUpload(tool)
                "web_wait_for" -> webWaitFor(tool)
                "web_snapshot" -> webSnapshot(tool)
                else -> error(tool.name, "Unsupported web session tool: ${tool.name}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Tool execution failed: ${tool.name}", e)
            error(tool.name, e.message ?: "Unknown error")
        }
    }

    private fun startWeb(tool: AITool): ToolResult {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            return error(tool.name, "Overlay permission is required for start_web.")
        }

        val initialUrl = param(tool, "url")?.takeIf { it.isNotBlank() } ?: "about:blank"
        val requestedUserAgent = param(tool, "user_agent")?.takeIf { it.isNotBlank() }
        val headers = parseHeaders(param(tool, "headers"))
        val sessionName = param(tool, "session_name")?.takeIf { it.isNotBlank() }

        val sessionId = UUID.randomUUID().toString()

        runOnMainSync {
            val session = createSessionOnMain(appContext, sessionId, sessionName, requestedUserAgent)
            sessions[sessionId] = session
            addSessionOrder(sessionId)

            ensureOverlayOnMain(appContext, initialExpanded = false)
            activeSessionId = sessionId
            setExpandedOnMain(false)
            navigateSessionOnMain(session, initialUrl, headers)
            ensureSessionAttachedOnMain(sessionId)
        }

        val payload =
            JSONObject()
                .put("session_id", sessionId)
                .put("status", "started")
                .put("url", initialUrl)
                .put("session_name", sessionName ?: "")
                .put("active_sessions", sessions.size)

        return ok(tool.name, payload)
    }

    private fun stopWeb(tool: AITool): ToolResult {
        val closeAll = boolParam(tool, "close_all", false)

        if (closeAll) {
            val ids = listSessionIdsInOrder().ifEmpty { sessions.keys.toList() }
            var closed = 0
            ids.forEach { id ->
                if (closeSession(id)) {
                    closed++
                }
            }

            val payload =
                JSONObject()
                    .put("status", "stopped")
                    .put("close_all", true)
                    .put("closed_count", closed)
                    .put("active_sessions", sessions.size)
            return ok(tool.name, payload)
        }

        val requestedSessionId = param(tool, "session_id")
        val targetSession = getSession(requestedSessionId)
            ?: return if (requestedSessionId.isNullOrBlank()) {
                error(tool.name, "No active web session. Start one first or pass session_id.")
            } else {
                error(tool.name, "Session not found: $requestedSessionId")
            }

        val closed = closeSession(targetSession.id)
        if (!closed) {
            return error(tool.name, "Session not found: ${targetSession.id}")
        }

        val payload =
            JSONObject()
                .put("status", "stopped")
                .put("session_id", targetSession.id)
                .put("active_sessions", sessions.size)

        return ok(tool.name, payload)
    }

    private fun webNavigate(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val targetUrl = param(tool, "url")
        if (targetUrl.isNullOrBlank()) {
            return error(tool.name, "url is required")
        }

        val headers = parseHeaders(param(tool, "headers"))

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
            navigateSessionOnMain(session, targetUrl, headers)
        }

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "navigating")
                .put("url", targetUrl)

        return ok(tool.name, payload)
    }

    private fun webEval(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val script = param(tool, "script")
        if (script.isNullOrBlank()) {
            return error(tool.name, "script is required")
        }

        val timeoutMs = longParam(tool, "timeout_ms", DEFAULT_TIMEOUT_MS)

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        val rawResult = evaluateJavascriptSync(session.webView, script, timeoutMs)
        val decodedResult = decodeJsResult(rawResult)

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "ok")
                .put("result", decodedResult)
                .put("raw_result", rawResult)

        return ok(tool.name, payload)
    }

    private fun webClick(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
        val element = param(tool, "element")?.trim()?.takeIf { it.isNotBlank() }
        if (ref.isNullOrBlank()) {
            return error(tool.name, "ref is required")
        }

        val buttonRaw = param(tool, "button")?.trim()
        val button =
            when {
                buttonRaw.isNullOrBlank() -> "left"
                buttonRaw == "left" || buttonRaw == "right" || buttonRaw == "middle" -> buttonRaw
                else -> return error(tool.name, "button must be one of: left, right, middle")
            }

        val doubleClick = boolParam(tool, "doubleClick", false)

        val (modifiers, invalidModifiers) = parseClickModifiers(param(tool, "modifiers"))
        if (invalidModifiers.isNotEmpty()) {
            return error(
                tool.name,
                "Invalid modifiers: ${invalidModifiers.joinToString(", ")}. Allowed: Alt, Control, ControlOrMeta, Meta, Shift"
            )
        }

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        val beforeUrl = readCurrentUrl(session.webView, session.currentUrl)
        val downloadMarker = session.lastDownloadEventAt
        val jsResult =
            dispatchClickByRef(
                webView = session.webView,
                ref = ref,
                button = button,
                modifiers = modifiers,
                doubleClick = doubleClick
            )
        if (jsResult?.optBoolean("ok", false) != true) {
            if (jsResult?.optString("error") == "ref_not_found") {
                return error(
                    tool.name,
                    "Ref $ref not found in the current page snapshot. Try capturing new snapshot."
                )
            }
            return error(
                tool.name,
                "Click failed: ${jsResult?.optString("error") ?: "unknown"}"
            )
        }

        val downloadEvent = waitForClickCompletion(session, beforeUrl, downloadMarker)
        if (downloadEvent?.status == "failed") {
            return error(
                tool.name,
                "Click triggered a download, but it failed: ${downloadEvent.error ?: downloadEvent.fileName}"
            )
        }

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "ok")
                .put("ref", ref)
                .put("button", button)
                .put("doubleClick", doubleClick)
                .put("result", jsResult)
        if (modifiers.isNotEmpty()) {
            payload.put("modifiers", JSONArray(modifiers.toList()))
        }
        if (!element.isNullOrBlank()) {
            payload.put("element", element)
        }
        if (downloadEvent != null) {
            payload.put("download", downloadEvent.toJson())
        }

        return ok(tool.name, payload)
    }

    private fun dispatchClickByRef(
        webView: WebView,
        ref: String,
        button: String,
        modifiers: Set<String>,
        doubleClick: Boolean
    ): JSONObject? {
        val buttonValue =
            when (button) {
                "middle" -> 1
                "right" -> 2
                else -> 0
            }
        val buttonsValue =
            when (button) {
                "middle" -> 4
                "right" -> 2
                else -> 1
            }

        val altKey = modifiers.contains("Alt")
        val controlKey = modifiers.contains("Control") || modifiers.contains("ControlOrMeta")
        val metaKey = modifiers.contains("Meta") || modifiers.contains("ControlOrMeta")
        val shiftKey = modifiers.contains("Shift")

        val script =
            """
            (function() {
                try {
                    const refValue = ${quoteJs(ref)};
                    const list = Array.from(document.querySelectorAll('[aria-ref]')).filter((el) => {
                        return String(el.getAttribute('aria-ref') || '') === refValue;
                    });
                    if (!list.length) {
                        return JSON.stringify({ ok: false, error: "ref_not_found", ref: refValue });
                    }
                    const target = list[0];
                    const anchor = target.closest('a[href]');
                    try { target.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const rect = target.getBoundingClientRect();
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;

                    try { target.focus({ preventScroll: true }); } catch (_) {}

                    const buttonValue = ${buttonValue};
                    const buttonsValue = ${buttonsValue};
                    const altKey = ${if (altKey) "true" else "false"};
                    const ctrlKey = ${if (controlKey) "true" else "false"};
                    const metaKey = ${if (metaKey) "true" else "false"};
                    const shiftKey = ${if (shiftKey) "true" else "false"};

                    function emit(type, detail) {
                        try {
                            target.dispatchEvent(new MouseEvent(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                view: window,
                                detail: detail,
                                clientX: x,
                                clientY: y,
                                screenX: x,
                                screenY: y,
                                button: buttonValue,
                                buttons: buttonsValue,
                                altKey,
                                ctrlKey,
                                metaKey,
                                shiftKey
                            }));
                        } catch (_) {}
                    }

                    function clickOnce(detail) {
                        emit("mousedown", detail);
                        emit("mouseup", detail);
                        emit("click", detail);
                    }

                    let activationMethod = "mouse_event";
                    let activationTag = String(target.tagName || "").toLowerCase();
                    const nativeAnchorClickEligible = !${if (doubleClick) "true" else "false"} &&
                        buttonValue === 0 && !altKey && !ctrlKey && !metaKey && !shiftKey &&
                        !!anchor && typeof anchor.click === "function";

                    if (nativeAnchorClickEligible) {
                        activationMethod = "native_anchor_click";
                        activationTag = String(anchor.tagName || "").toLowerCase();
                        anchor.click();
                    } else if (${if (doubleClick) "true" else "false"}) {
                        clickOnce(1);
                        clickOnce(2);
                        emit("dblclick", 2);
                    } else {
                        clickOnce(1);
                    }

                    return JSON.stringify({
                        ok: true,
                        ref: refValue,
                        button: ${quoteJs(button)},
                        doubleClick: ${if (doubleClick) "true" else "false"},
                        tag: String(target.tagName || "").toLowerCase(),
                        activationMethod,
                        activationTag,
                        href: anchor ? String(anchor.href || "") : ""
                    });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()

        return try {
            val raw = evaluateJavascriptSync(webView, script, DEFAULT_TIMEOUT_MS.coerceIn(2_000L, 8_000L))
            val decoded = decodeJsResult(raw)
            JSONObject(decoded)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "click_dispatch_error")
        }
    }

    private fun parseClickModifiers(raw: String?): Pair<Set<String>, List<String>> {
        if (raw.isNullOrBlank()) {
            return emptySet<String>() to emptyList()
        }

        val allowed = setOf("Alt", "Control", "ControlOrMeta", "Meta", "Shift")
        val parsed = linkedSetOf<String>()
        val invalid = mutableListOf<String>()

        val arr =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                return emptySet<String>() to listOf(raw)
            }

        for (i in 0 until arr.length()) {
            val token = arr.optString(i, "").trim()
            if (token in allowed) {
                parsed.add(token)
            } else {
                invalid.add(token.ifBlank { "<empty>" })
            }
        }

        return parsed to invalid
    }

    private fun readCurrentUrl(webView: WebView, fallback: String): String {
        return runCatching {
            val raw =
                evaluateJavascriptSync(
                    webView,
                    "(function(){ return String(location.href || ''); })();",
                    2_000L
                )
            decodeJsResult(raw)
        }.getOrDefault(fallback)
    }

    private fun waitForClickCompletion(
        session: WebSession,
        beforeUrl: String,
        downloadMarker: Long
    ): WebDownloadEvent? {
        Thread.sleep(350)
        val deadline = System.currentTimeMillis() + 10_000L
        val readyWithoutNavigationGraceDeadline = System.currentTimeMillis() + 1_500L
        var urlChanged = false

        while (System.currentTimeMillis() < deadline) {
            val latestDownload = session.lastDownloadEvent
            if (latestDownload != null && session.lastDownloadEventAt > downloadMarker) {
                return latestDownload
            }

            try {
                val raw =
                    evaluateJavascriptSync(
                        session.webView,
                        "(function(){ return JSON.stringify({ url: String(location.href || ''), ready: String(document.readyState || '') }); })();",
                        2_000L
                    )
                val decoded = decodeJsResult(raw)
                val state = JSONObject(decoded)
                val currentUrl = state.optString("url", "")
                val ready = state.optString("ready", "")

                if (currentUrl != beforeUrl) {
                    urlChanged = true
                }

                if (urlChanged && ready == "complete") {
                    Thread.sleep(500)
                    return session.lastDownloadEvent?.takeIf { session.lastDownloadEventAt > downloadMarker }
                }

                if (!urlChanged && ready == "complete" && System.currentTimeMillis() >= readyWithoutNavigationGraceDeadline) {
                    return session.lastDownloadEvent?.takeIf { session.lastDownloadEventAt > downloadMarker }
                }
            } catch (_: Exception) {
                return session.lastDownloadEvent?.takeIf { session.lastDownloadEventAt > downloadMarker }
            }

            Thread.sleep(120)
        }

        return session.lastDownloadEvent?.takeIf { session.lastDownloadEventAt > downloadMarker }
    }

    private fun webFill(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val selector = param(tool, "selector")
        val value = param(tool, "value")
        if (selector.isNullOrBlank()) {
            return error(tool.name, "selector is required")
        }
        if (value == null) {
            return error(tool.name, "value is required")
        }

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        val script =
            """
            (function() {
                try {
                    const el = document.querySelector(${quoteJs(selector)});
                    if (!el) return JSON.stringify({ ok: false, error: "element_not_found" });
                    el.focus();
                    el.value = ${quoteJs(value)};
                    el.dispatchEvent(new Event("input", { bubbles: true }));
                    el.dispatchEvent(new Event("change", { bubbles: true }));
                    return JSON.stringify({ ok: true, tag: (el.tagName || "").toLowerCase() });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()

        val raw = evaluateJavascriptSync(session.webView, script, DEFAULT_TIMEOUT_MS)
        val decoded = decodeJsResult(raw)

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "ok")
                .put("result", decoded)

        return ok(tool.name, payload)
    }

    private fun webFileUpload(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val rawPaths = param(tool, "paths")?.trim().orEmpty()
        val shouldCancel = rawPaths.isBlank()

        val files: List<File> =
            if (shouldCancel) {
                emptyList()
            } else {
                val pathList = parseStringArrayParam(rawPaths)
                    ?: return error(tool.name, "paths must be a JSON array")

                val resolved = mutableListOf<File>()
                for (rawPath in pathList) {
                    val path = rawPath.trim()
                    if (path.isBlank()) {
                        return error(tool.name, "paths contains an empty item")
                    }
                    val file = File(path)
                    if (!file.isAbsolute) {
                        return error(tool.name, "path must be absolute: $path")
                    }
                    if (!file.exists() || !file.isFile) {
                        return error(tool.name, "file does not exist: $path")
                    }
                    resolved.add(file)
                }
                resolved
            }

        val callbackResult =
            runOnMainSync {
                ensureSessionAttachedOnMain(session.id)

                val callback = session.pendingFileChooserCallback
                    ?: return@runOnMainSync error(tool.name, "No file chooser is active")

                return@runOnMainSync try {
                    if (shouldCancel) {
                        callback.onReceiveValue(null)
                        session.pendingFileChooserCallback = null

                        val payload =
                            JSONObject()
                                .put("session_id", session.id)
                                .put("status", "ok")
                                .put("cancelled", true)
                        ok(tool.name, payload)
                    } else {
                        val uris = files.map { Uri.fromFile(it) }.toTypedArray()
                        callback.onReceiveValue(uris)
                        session.pendingFileChooserCallback = null

                        val uploaded = JSONArray()
                        files.forEach { uploaded.put(it.absolutePath) }

                        val payload =
                            JSONObject()
                                .put("session_id", session.id)
                                .put("status", "ok")
                                .put("uploaded_count", files.size)
                                .put("paths", uploaded)
                        ok(tool.name, payload)
                    }
                } catch (e: Exception) {
                    error(tool.name, "Failed to resolve file chooser: ${e.message}")
                }
            }

        return callbackResult
    }

    private fun webWaitFor(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val selector = param(tool, "selector")?.takeIf { it.isNotBlank() }
        val timeoutMs = longParam(tool, "timeout_ms", DEFAULT_TIMEOUT_MS).coerceAtLeast(200)
        val deadline = System.currentTimeMillis() + timeoutMs

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        while (System.currentTimeMillis() < deadline) {
            if (selector.isNullOrBlank()) {
                if (session.pageLoaded) {
                    val payload =
                        JSONObject()
                            .put("session_id", session.id)
                            .put("status", "ready")
                            .put("page_loaded", true)
                    return ok(tool.name, payload)
                }
            } else {
                runOnMainSync {
                    ensureSessionAttachedOnMain(session.id)
                }

                val script =
                    """
                    (function() {
                        try {
                            return !!document.querySelector(${quoteJs(selector)});
                        } catch (e) {
                            return false;
                        }
                    })();
                    """.trimIndent()

                val result = evaluateJavascriptSync(session.webView, script, 2_000)
                if (result == "true") {
                    val payload =
                        JSONObject()
                            .put("session_id", session.id)
                            .put("status", "ready")
                            .put("selector", selector)
                    return ok(tool.name, payload)
                }
            }
            Thread.sleep(120)
        }

        return error(tool.name, "Timeout waiting for condition")
    }

    private fun webSnapshot(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val includeLinks = boolParam(tool, "include_links", true)
        val includeImages = boolParam(tool, "include_images", false)

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        val script =
            """
            (function() {
                try {
                    const includeLinks = ${if (includeLinks) "true" else "false"};
                    const includeImages = ${if (includeImages) "true" else "false"};
                    const title = document.title || "";
                    const url = location.href || "";
                    let text = (document.body && document.body.innerText) ? document.body.innerText : "";
                    text = text.replace(/\n{3,}/g, "\n\n").trim();
                    if (text.length > 20000) {
                        text = text.slice(0, 20000) + "\n...(truncated)";
                    }

                    const interactiveSelector = [
                        "a[href]",
                        "button",
                        "input",
                        "select",
                        "textarea",
                        "summary",
                        "[role='button']",
                        "[onclick]",
                        "[tabindex]"
                    ].join(",");

                    const candidates = Array.from(document.querySelectorAll(interactiveSelector));
                    let nextRef = 1;
                    const existingRefNumbers = Array.from(document.querySelectorAll("[aria-ref]")).map((el) => {
                        const raw = String(el.getAttribute("aria-ref") || "");
                        const m = /^e(\d+)$/.exec(raw);
                        return m ? parseInt(m[1], 10) : 0;
                    }).filter((n) => Number.isFinite(n) && n > 0);
                    if (existingRefNumbers.length) {
                        nextRef = Math.max.apply(null, existingRefNumbers) + 1;
                    }

                    const refs = [];
                    candidates.slice(0, 200).forEach((el) => {
                        let ref = String(el.getAttribute("aria-ref") || "");
                        if (!ref) {
                            ref = "e" + (nextRef++);
                            try { el.setAttribute("aria-ref", ref); } catch (_) {}
                        }

                        const tag = String(el.tagName || "").toLowerCase();
                        const label = (
                            el.innerText ||
                            el.textContent ||
                            el.getAttribute("aria-label") ||
                            el.getAttribute("title") ||
                            el.getAttribute("name") ||
                            el.getAttribute("value") ||
                            ""
                        ).replace(/\s+/g, " ").trim();

                        refs.push({
                            ref,
                            tag,
                            label: label.slice(0, 80)
                        });
                    });

                    const lines = [];
                    lines.push("Title: " + title);
                    lines.push("URL: " + url);
                    lines.push("");
                    lines.push("Content:");
                    lines.push(text);

                    if (refs.length) {
                        lines.push("");
                        lines.push("Elements:");
                        refs.slice(0, 120).forEach((item) => {
                            const readable = item.label || "(no label)";
                            lines.push("[" + item.ref + "] <" + item.tag + "> " + readable);
                        });
                    }

                    if (includeLinks) {
                        const links = Array.from(document.querySelectorAll("a[href]")).slice(0, 100);
                        lines.push("");
                        lines.push("Results:");
                        links.forEach((a, i) => {
                            const href = a.href || "";
                            const t = (a.innerText || a.textContent || "").replace(/\s+/g, " ").trim() || href;
                            lines.push("[" + (i + 1) + "] " + t + " - " + href);
                        });
                    }

                    if (includeImages) {
                        const imgs = Array.from(document.querySelectorAll("img[src]")).slice(0, 100);
                        lines.push("");
                        lines.push("Images:");
                        imgs.forEach((img, i) => {
                            const src = img.src || "";
                            const alt = (img.alt || "").replace(/\s+/g, " ").trim();
                            lines.push("[" + (i + 1) + "] " + (alt || "image") + " - " + src);
                        });
                    }

                    return lines.join("\n");
                } catch (e) {
                    return "Snapshot error: " + String(e);
                }
            })();
            """.trimIndent()

        val raw = evaluateJavascriptSync(session.webView, script, DEFAULT_TIMEOUT_MS)
        val snapshot = decodeJsResult(raw)

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "ok")
                .put("snapshot", snapshot)

        return ok(tool.name, payload)
    }

    private fun createSessionOnMain(
        appContext: Context,
        sessionId: String,
        sessionName: String?,
        customUserAgent: String?
    ): WebSession {
        val webView = WebView(appContext)
        val session =
            WebSession(
                id = sessionId,
                webView = webView,
                sessionName = sessionName,
                customUserAgent = customUserAgent
            )
        configureWebView(session, resolveUserAgent(customUserAgent))
        return session
    }

    private fun configureWebView(session: WebSession, userAgent: String) {
        with(session.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    safeBrowsingEnabled = true
                } catch (e: Throwable) {
                    AppLogger.w(TAG, "Failed to enable safe browsing: ${e.message}")
                }
            }
        }
        applySessionUserAgent(session, userAgent)
        configureCookiePolicy(session.webView)

        session.webView.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isLongClickable = true
            contentDescription = context.getString(R.string.web_session_accessibility_web_content)
            addJavascriptInterface(WebDownloadBridge(session), "OperitWebDownloadBridge")
            setDownloadListener(createDownloadListener(session))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isScreenReaderFocusable = true
            }
        }

        session.webView.webChromeClient =
            object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    val message = resultMsg ?: return false
                    val transport = message.obj as? WebView.WebViewTransport ?: return false
                    val popupSession = runCatching { createPopupSessionOnMain(session) }.getOrNull() ?: return false
                    transport.webView = popupSession.webView
                    message.sendToTarget()
                    setExpandedOnMain(true)
                    refreshSessionUiOnMain(popupSession.id)
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    super.onCloseWindow(window)
                    val popupSession = window?.let(::findSessionByWebView)
                    if (popupSession != null) {
                        closeSession(popupSession.id)
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    session.pageTitle = title.orEmpty()
                    refreshSessionUiOnMain(session.id)
                    ioScope.launch {
                        historyStore.updateTitle(session.currentUrl, session.pageTitle)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: WebChromeClient.FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) {
                        return false
                    }

                    session.pendingFileChooserCallback?.onReceiveValue(null)
                    session.pendingFileChooserCallback = filePathCallback
                    session.lastFileChooserRequestAt = System.currentTimeMillis()

                    AppLogger.d(
                        TAG,
                        "Captured file chooser request for session=${session.id}, " +
                            "mode=${fileChooserParams?.mode}, multiple=${fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE}"
                    )
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) {
                        return
                    }
                    handleWebPermissionRequest(request)
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    if (origin.isNullOrBlank() || callback == null) {
                        callback?.invoke(origin.orEmpty(), false, false)
                        return
                    }
                    handleGeolocationPermissionRequest(origin, callback)
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?
                ): Boolean {
                    AppLogger.d(TAG, "web_session js alert: ${message.orEmpty()}")
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?
                ): Boolean {
                    AppLogger.d(TAG, "web_session js confirm: ${message.orEmpty()}")
                    result?.confirm()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: android.webkit.JsPromptResult?
                ): Boolean {
                    AppLogger.d(TAG, "web_session js prompt: ${message.orEmpty()}")
                    result?.confirm(defaultValue)
                    return true
                }
            }

        session.webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    session.currentUrl = url
                    session.pageLoaded = false
                    session.isLoading = true
                    session.hasSslError = false
                    syncNavigationStateUi(session)
                }

                override fun onPageCommitVisible(view: WebView, url: String) {
                    super.onPageCommitVisible(view, url)
                    session.currentUrl = url
                    refreshNavigationStateFromWebView(view, session)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    session.currentUrl = url
                    session.pageTitle = view.title ?: ""
                    session.pageLoaded = true
                    session.isLoading = false
                    refreshNavigationStateFromWebView(view, session)
                    injectDownloadHelper(view)
                    ioScope.launch {
                        historyStore.updateTitle(url, session.pageTitle)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "blob") {
                        injectBlobDownloaderScript(session, uri.toString())
                        return true
                    }
                    if (scheme == "data") {
                        handleInlineDownload(
                            session = session,
                            base64Data = uri.toString(),
                            fileName = "download_${System.currentTimeMillis()}",
                            mimeType = guessMimeTypeFromDataUrl(uri.toString()),
                            type = "data"
                        )
                        return true
                    }
                    return handleNavigationOverrideOnMain(session, uri)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    session.currentUrl = url
                    val pageTitle = view.title.orEmpty()
                    refreshNavigationStateFromWebView(view, session)
                    ioScope.launch {
                        historyStore.recordVisit(url, pageTitle, isReload)
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: android.net.http.SslError
                ) {
                    AppLogger.w(
                        TAG,
                        "web_session SSL error, proceeding anyway. " +
                            "session=${session.id}, url=${error.url}, primaryError=${error.primaryError}"
                    )
                    session.hasSslError = true
                    updateNavigationState(session)
                    refreshSessionUiOnMain(session.id)
                    handler.proceed()
                }
            }
    }

    private fun ensureOverlayOnMain(
        appContext: Context,
        initialExpanded: Boolean = true
    ): WebSessionBrowserHost {
        browserHost?.let { return it }

        synchronized(overlayLock) {
            browserHost?.let { return it }

            val host =
                WebSessionBrowserHost(
                    appContext = appContext,
                    store = historyStore,
                    callbacks = createBrowserHostCallbacks(appContext)
                )
            browserHost = host
            host.ensureCreated(initialExpanded = initialExpanded)
            refreshSessionUiOnMain()
            return host
        }
    }

    private fun createBrowserHostCallbacks(appContext: Context): WebSessionBrowserHost.Callbacks =
        object : WebSessionBrowserHost.Callbacks {
            override fun onNavigate(url: String) {
                runOnMainSync {
                    openUrlOnMain(appContext, url)
                }
            }

            override fun onBack() {
                runOnMainSync {
                    val session = getActiveSessionOnMain() ?: return@runOnMainSync
                    ensureSessionAttachedOnMain(session.id)
                    if (session.webView.canGoBack()) {
                        session.webView.goBack()
                    }
                    refreshNavigationStateAsync(session)
                }
            }

            override fun onForward() {
                runOnMainSync {
                    val session = getActiveSessionOnMain() ?: return@runOnMainSync
                    ensureSessionAttachedOnMain(session.id)
                    if (session.webView.canGoForward()) {
                        session.webView.goForward()
                    }
                    refreshNavigationStateAsync(session)
                }
            }

            override fun onRefreshOrStop() {
                runOnMainSync {
                    val session = getActiveSessionOnMain() ?: return@runOnMainSync
                    ensureSessionAttachedOnMain(session.id)
                    if (session.isLoading) {
                        session.webView.stopLoading()
                        session.isLoading = false
                    } else {
                        session.pageLoaded = false
                        session.isLoading = true
                        session.webView.reload()
                    }
                    refreshNavigationStateAsync(session)
                }
            }

            override fun onSelectTab(sessionId: String) {
                runOnMainSync {
                    ensureSessionAttachedOnMain(sessionId)
                }
            }

            override fun onCloseTab(sessionId: String) {
                closeSession(sessionId)
            }

            override fun onNewTab() {
                runOnMainSync {
                    createSessionTabOnMain(appContext, initialUrl = "about:blank")
                }
            }

            override fun onMinimize() {
                runOnMainSync {
                    setExpandedOnMain(false)
                }
            }

            override fun onCloseCurrentTab() {
                activeSessionId?.let { closeSession(it) }
            }

            override fun onCloseAllTabs() {
                val ids = listSessionIdsInOrder().ifEmpty { sessions.keys.toList() }
                ids.forEach { closeSession(it) }
            }

            override fun onToggleBookmark(url: String, title: String) {
                ioScope.launch {
                    historyStore.toggleBookmark(url, title)
                }
            }

            override fun onRemoveBookmark(url: String) {
                ioScope.launch {
                    historyStore.removeBookmark(url)
                }
            }

            override fun onSelectSessionHistory(index: Int) {
                runOnMainSync {
                    val session = getActiveSessionOnMain() ?: return@runOnMainSync
                    ensureSessionAttachedOnMain(session.id)
                    val historyList = session.webView.copyBackForwardList()
                    val delta = index - historyList.currentIndex
                    if (delta != 0 && session.webView.canGoBackOrForward(delta)) {
                        session.webView.goBackOrForward(delta)
                        refreshNavigationStateAsync(session)
                    }
                }
            }

            override fun onOpenUrl(url: String) {
                runOnMainSync {
                    openUrlOnMain(appContext, url)
                }
            }

            override fun onClearHistory() {
                ioScope.launch {
                    historyStore.clearHistory()
                }
            }

            override fun onToggleDesktopMode() {
                setDesktopModeEnabled(!desktopModeEnabled)
            }
        }

    private fun destroyOverlayOnMain() {
        browserHost?.destroy()
        browserHost = null
        activeSessionId = null
    }

    private fun setExpandedOnMain(expanded: Boolean) {
        browserHost?.setExpanded(expanded)
        keepActiveWebViewRunningOnMain(expanded)
        if (expanded) {
            refreshSessionUiOnMain()
        }
    }

    private fun keepActiveWebViewRunningOnMain(expanded: Boolean) {
        val session = getActiveSessionOnMain() ?: return
        try {
            session.webView.onResume()
            session.webView.resumeTimers()
            session.webView.visibility = View.VISIBLE
            session.webView.alpha = 1f
            if (expanded) {
                if (!session.webView.hasFocus()) {
                    session.webView.requestFocus()
                }
            } else {
                session.webView.clearFocus()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to keep active WebView running: ${e.message}")
        }
    }

    private fun createSessionTabOnMain(
        appContext: Context,
        initialUrl: String,
        sessionName: String? = null,
        customUserAgent: String? = null
    ): WebSession {
        val sessionId = UUID.randomUUID().toString()
        val session = createSessionOnMain(appContext, sessionId, sessionName, customUserAgent)
        sessions[sessionId] = session
        addSessionOrder(sessionId)
        activeSessionId = sessionId
        ensureOverlayOnMain(appContext)
        navigateSessionOnMain(session, initialUrl)
        ensureSessionAttachedOnMain(sessionId)
        return session
    }

    private fun navigateSessionOnMain(
        session: WebSession,
        targetUrl: String,
        headers: Map<String, String> = emptyMap()
    ) {
        session.pageLoaded = false
        session.isLoading = true
        session.currentUrl = targetUrl
        session.hasSslError = false
        updateNavigationState(session)
        refreshSessionUiOnMain(session.id)
        if (headers.isNotEmpty()) {
            session.webView.loadUrl(targetUrl, headers)
        } else {
            session.webView.loadUrl(targetUrl)
        }
        refreshNavigationStateAsync(session)
    }

    private fun openUrlOnMain(appContext: Context, url: String) {
        val existingSession = getActiveSessionOnMain()
        val session = existingSession ?: createSessionTabOnMain(appContext, initialUrl = url)
        if (existingSession != null) {
            navigateSessionOnMain(session, url)
        }
        ensureSessionAttachedOnMain(session.id)
    }

    private fun activateSessionOnMain(sessionId: String) {
        val session = sessions[sessionId] ?: return
        ensureOverlayOnMain(context.applicationContext)
        activeSessionId = sessionId
        browserHost?.attachActiveWebView(session.webView)
        updateNavigationState(session)
        refreshSessionUiOnMain(session.id)
    }

    private fun ensureSessionAttachedOnMain(sessionId: String) {
        val session = sessions[sessionId] ?: return
        ensureOverlayOnMain(context.applicationContext)
        activeSessionId = sessionId
        browserHost?.attachActiveWebView(session.webView)
        runCatching {
            session.webView.onResume()
            session.webView.resumeTimers()
            session.webView.visibility = View.VISIBLE
            session.webView.alpha = 1f
        }
        updateNavigationState(session)
        refreshSessionUiOnMain(session.id)
    }

    private fun refreshSessionUiOnMain(sessionId: String? = null) {
        sessionId?.let { id ->
            sessions[id]?.let(::updateNavigationState)
        }

        val host = browserHost ?: return
        val resolvedActiveId = resolvePreferredSessionId()
        activeSessionId = resolvedActiveId
        host.attachActiveWebView(resolvedActiveId?.let { sessions[it]?.webView })
        host.updateBrowserState(buildBrowserState(resolvedActiveId))
    }

    private fun buildBrowserState(preferredSessionId: String?): WebSessionBrowserState {
        val activeId =
            preferredSessionId?.takeIf { sessions.containsKey(it) }
                ?: listSessionIdsInOrder().firstOrNull { sessions.containsKey(it) }
                ?: sessions.keys.firstOrNull()
        val activeSession = activeId?.let { sessions[it] }
        val orderedIds =
            linkedSetOf<String>().apply {
                addAll(listSessionIdsInOrder())
                addAll(sessions.keys)
            }

        return WebSessionBrowserState(
            activeSessionId = activeId,
            pageTitle = activeSession?.pageTitle.orEmpty(),
            currentUrl = activeSession?.currentUrl?.ifBlank { "about:blank" } ?: "about:blank",
            canGoBack = activeSession?.canGoBack == true,
            canGoForward = activeSession?.canGoForward == true,
            isLoading = activeSession?.isLoading == true,
            hasSslError = activeSession?.hasSslError == true,
            isDesktopMode = desktopModeEnabled,
            tabs =
                orderedIds.mapNotNull { id ->
                    sessions[id]?.let { session ->
                        WebSessionBrowserTab(
                            sessionId = session.id,
                            title = sessionDisplayTitle(session),
                            url = session.currentUrl.ifBlank { "about:blank" },
                            isActive = session.id == activeId,
                            hasSslError = session.hasSslError
                        )
                    }
                },
            sessionHistory =
                activeSession?.let { buildSessionHistory(it.webView) } ?: emptyList()
        )
    }

    private fun buildSessionHistory(webView: WebView): List<WebSessionSessionHistoryItem> {
        val historyList = webView.copyBackForwardList()
        if (historyList.size == 0) {
            return emptyList()
        }

        return buildList(historyList.size) {
            for (index in 0 until historyList.size) {
                val item = historyList.getItemAtIndex(index)
                val url = item?.url.orEmpty().ifBlank { "about:blank" }
                val title = item?.title.orEmpty().ifBlank { url }
                add(
                    WebSessionSessionHistoryItem(
                        index = index,
                        title = title,
                        url = url,
                        isCurrent = index == historyList.currentIndex
                    )
                )
            }
        }
    }

    private fun sessionDisplayTitle(session: WebSession): String {
        val base =
            when {
                session.pageTitle.isNotBlank() -> session.pageTitle
                !session.sessionName.isNullOrBlank() -> session.sessionName
                session.currentUrl.isNotBlank() -> session.currentUrl
                else -> "about:blank"
            }
        val sslBadge = context.getString(R.string.web_ssl_error_badge)
        return if (session.hasSslError) "$sslBadge · $base" else base
    }

    private fun getActiveSessionOnMain(): WebSession? =
        resolvePreferredSessionId()?.let { sessions[it] }

    private fun updateNavigationState(session: WebSession) {
        session.canGoBack = runCatching { session.webView.canGoBack() }.getOrDefault(false)
        session.canGoForward = runCatching { session.webView.canGoForward() }.getOrDefault(false)
    }

    private fun syncNavigationStateUi(session: WebSession) {
        updateNavigationState(session)
        refreshSessionUiOnMain(session.id)
    }

    private fun refreshNavigationStateFromWebView(view: WebView, session: WebSession) {
        syncNavigationStateUi(session)
        view.post {
            if (session.webView === view) {
                syncNavigationStateUi(session)
            }
        }
    }

    private fun refreshNavigationStateAsync(session: WebSession) {
        session.webView.post {
            syncNavigationStateUi(session)
        }
    }

    private fun ensureDesktopModeInitialized() {
        if (desktopModeInitialized) {
            return
        }

        synchronized(sessionConfigLock) {
            if (desktopModeInitialized) {
                return
            }
            desktopModeEnabled =
                runBlocking(Dispatchers.IO) {
                    historyStore.desktopModeFlow.first()
                }
            desktopModeInitialized = true
        }
    }

    private fun resolveUserAgent(customUserAgent: String?): String =
        customUserAgent ?: if (desktopModeEnabled) DEFAULT_USER_AGENT else MOBILE_USER_AGENT

    private fun applySessionUserAgent(session: WebSession, userAgent: String) {
        with(session.webView.settings) {
            userAgentString = userAgent
            useWideViewPort = desktopModeEnabled
            loadWithOverviewMode = desktopModeEnabled
        }
    }

    private fun configureCookiePolicy(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun createPopupSessionOnMain(parentSession: WebSession): WebSession {
        val popupSession =
            createSessionOnMain(
                appContext = context.applicationContext,
                sessionId = UUID.randomUUID().toString(),
                sessionName = parentSession.sessionName,
                customUserAgent = parentSession.customUserAgent
            )
        sessions[popupSession.id] = popupSession
        addSessionOrder(popupSession.id)
        activeSessionId = popupSession.id
        ensureOverlayOnMain(context.applicationContext)
        browserHost?.attachActiveWebView(popupSession.webView)
        refreshSessionUiOnMain(popupSession.id)
        return popupSession
    }

    private fun findSessionByWebView(webView: WebView): WebSession? =
        sessions.values.firstOrNull { it.webView === webView }

    private fun handleNavigationOverrideOnMain(session: WebSession, uri: Uri): Boolean {
        val rawUrl = uri.toString()
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return when (scheme) {
            "http", "https" -> false
            "about" -> false
            "intent" -> handleIntentSchemeOnMain(session, rawUrl)
            else -> {
                if (!openExternalIntentOnMain(Intent(Intent.ACTION_VIEW, uri))) {
                    showToast(context.getString(R.string.web_session_external_open_failed, rawUrl))
                }
                true
            }
        }
    }

    private fun handleIntentSchemeOnMain(session: WebSession, rawUrl: String): Boolean {
        val intent =
            runCatching { Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME) }.getOrElse { error ->
                AppLogger.w(TAG, "Failed to parse intent url: ${error.message}")
                showToast(context.getString(R.string.web_session_external_open_failed, rawUrl))
                return true
            }

        val fallbackUrl = intent.getStringExtra("browser_fallback_url")?.takeIf { it.isNotBlank() }
        val sanitizedIntent =
            intent.apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }

        if (openExternalIntentOnMain(sanitizedIntent)) {
            return true
        }

        if (!fallbackUrl.isNullOrBlank()) {
            navigateSessionOnMain(session, fallbackUrl)
            return true
        }

        showToast(context.getString(R.string.web_session_external_open_failed, rawUrl))
        return true
    }

    private fun openExternalIntentOnMain(intent: Intent): Boolean {
        val currentActivity = ActivityLifecycleManager.getCurrentActivity()
        return try {
            if (currentActivity != null && !currentActivity.isFinishing && !currentActivity.isDestroyed) {
                currentActivity.startActivity(Intent(intent))
            } else {
                context.applicationContext.startActivity(
                    Intent(intent).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            true
        } catch (e: ActivityNotFoundException) {
            AppLogger.w(TAG, "No activity found for external navigation: ${e.message}")
            false
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to open external navigation: ${e.message}")
            false
        }
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val requestedResources = request.resources?.distinct().orEmpty()
        if (requestedResources.isEmpty()) {
            request.deny()
            return
        }

        val requiredPermissions =
            requestedResources
                .flatMap(::androidPermissionsForWebResource)
                .toCollection(LinkedHashSet())

        if (requiredPermissions.isEmpty()) {
            request.grant(requestedResources.toTypedArray())
            return
        }

        ioScope.launch {
            val permissionResults = ensureAndroidPermissions(requiredPermissions)
            val grantableResources =
                requestedResources
                    .filter { resource ->
                        val required = androidPermissionsForWebResource(resource)
                        required.isEmpty() || required.all { permissionResults[it] == true }
                    }
                    .toTypedArray()

            mainHandler.post {
                if (grantableResources.isNotEmpty()) {
                    request.grant(grantableResources)
                } else {
                    request.deny()
                    showToast(context.getString(R.string.web_session_permission_denied))
                }
            }
        }
    }

    private fun handleGeolocationPermissionRequest(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        ioScope.launch {
            val permissionResults =
                ensureAndroidPermissions(
                    listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            val granted =
                permissionResults[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissionResults[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            mainHandler.post {
                callback.invoke(origin, granted, false)
                if (!granted) {
                    showToast(context.getString(R.string.web_session_location_permission_denied))
                }
            }
        }
    }

    private suspend fun ensureAndroidPermissions(
        permissions: Collection<String>
    ): Map<String, Boolean> {
        val requested =
            permissions
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        if (requested.isEmpty()) {
            return emptyMap()
        }

        val currentResults =
            requested.associateWith { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        val missingPermissions = currentResults.filterValues { granted -> !granted }.keys
        if (missingPermissions.isEmpty()) {
            return currentResults
        }

        val requestedResults =
            WebSessionPermissionRequestCoordinator.requestPermissions(
                context = context.applicationContext,
                permissions = missingPermissions
            )

        return requested.associateWith { permission ->
            currentResults[permission] == true || requestedResults[permission] == true
        }
    }

    private fun androidPermissionsForWebResource(resource: String): List<String> =
        when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
            else -> emptyList()
        }

    private fun showToast(message: String) {
        if (message.isBlank()) {
            return
        }
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setDesktopModeEnabled(enabled: Boolean) {
        if (desktopModeEnabled == enabled) {
            return
        }

        desktopModeEnabled = enabled
        ioScope.launch {
            historyStore.setDesktopMode(enabled)
        }

        runOnMainSync {
            sessions.values.forEach { session ->
                if (session.customUserAgent == null) {
                    applySessionUserAgent(session, resolveUserAgent(null))
                }
            }

            val activeSession = getActiveSessionOnMain()
            if (activeSession != null && activeSession.customUserAgent == null) {
                activeSession.pageLoaded = false
                activeSession.isLoading = true
                activeSession.webView.reload()
                refreshNavigationStateAsync(activeSession)
            } else {
                refreshSessionUiOnMain(activeSession?.id)
            }
        }
    }

    private fun closeSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        removeSessionOrder(sessionId)

        runOnMainSync {
            if (activeSessionId == sessionId) {
                activeSessionId = null
                browserHost?.attachActiveWebView(null)
            }

            val parent = session.webView.parent
            if (parent is ViewGroup) {
                parent.removeView(session.webView)
            }
            session.pendingFileChooserCallback?.onReceiveValue(null)
            session.pendingFileChooserCallback = null
            cleanupWebViewOnMain(session.webView)

            val nextSessionId = listSessionIdsInOrder().firstOrNull { sessions.containsKey(it) }
            if (nextSessionId != null) {
                activateSessionOnMain(nextSessionId)
            } else {
                destroyOverlayOnMain()
            }
            refreshSessionUiOnMain()
        }

        return true
    }

    private fun cleanupWebViewOnMain(webView: WebView) {
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.removeAllViews()
            webView.destroy()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error during WebView cleanup: ${e.message}")
        }
    }

    private fun createDownloadListener(session: WebSession): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                when {
                    url.startsWith("blob:") -> {
                        injectBlobDownloaderScript(session, url)
                    }

                    url.startsWith("data:") -> {
                        handleInlineDownload(
                            session = session,
                            base64Data = url,
                            fileName = "",
                            mimeType = guessMimeTypeFromDataUrl(url).ifBlank { mimetype.orEmpty() },
                            type = "data"
                        )
                    }

                    else -> {
                        handleRegularDownload(
                            session = session,
                            url = url,
                            userAgent = userAgent,
                            contentDisposition = contentDisposition,
                            mimeType = mimetype
                        )
                    }
                }
            } catch (e: Exception) {
                recordDownloadEvent(
                    session,
                    WebDownloadEvent(
                        status = "failed",
                        type =
                            when {
                                url.startsWith("blob:") -> "blob"
                                url.startsWith("data:") -> "data"
                                else -> "http"
                            },
                        fileName =
                            if (url.startsWith("data:") || url.startsWith("blob:")) {
                                resolveInlineDownloadFileName("", mimetype.orEmpty().ifBlank { guessMimeTypeFromDataUrl(url) })
                            } else {
                                sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimetype))
                            },
                        url = url,
                        mimeType = mimetype,
                        error = e.message ?: "download_failed"
                    )
                )
                AppLogger.e(TAG, "Download failed for session=${session.id}: ${e.message}", e)
                mainHandler.post {
                    Toast.makeText(
                        context,
                        context.getString(R.string.download_failed, e.message ?: url),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun handleRegularDownload(
        session: WebSession,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))
        val destinationFile =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            setTitle(fileName)
            setDescription(context.getString(R.string.download_file_description))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
            request.addRequestHeader("Cookie", it)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        recordDownloadEvent(
            session,
            WebDownloadEvent(
                status = "started",
                type = "http",
                fileName = fileName,
                url = url,
                mimeType = mimeType,
                savedPath = destinationFile.absolutePath,
                downloadId = downloadId
            )
        )

        mainHandler.post {
            Toast.makeText(
                context,
                context.getString(R.string.download_started, fileName),
                Toast.LENGTH_SHORT
            ).show()
        }

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (completedId != downloadId) {
                        return
                    }

                    runCatching {
                        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                        cursor.use {
                            if (!it.moveToFirst()) {
                                recordDownloadEvent(
                                    session,
                                    WebDownloadEvent(
                                        status = "failed",
                                        type = "http",
                                        fileName = fileName,
                                        url = url,
                                        mimeType = mimeType,
                                        savedPath = destinationFile.absolutePath,
                                        downloadId = downloadId,
                                        error = "download_query_failed"
                                    )
                                )
                                return@use
                            }

                            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val localUriIndex = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val status =
                                if (statusIndex >= 0) it.getInt(statusIndex) else DownloadManager.STATUS_FAILED
                            val localUri = if (localUriIndex >= 0) it.getString(localUriIndex) else null
                            val resolvedPath = Uri.parse(localUri ?: "").path ?: destinationFile.absolutePath

                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                recordDownloadEvent(
                                    session,
                                    WebDownloadEvent(
                                        status = "completed",
                                        type = "http",
                                        fileName = fileName,
                                        url = url,
                                        mimeType = mimeType,
                                        savedPath = resolvedPath,
                                        downloadId = downloadId
                                    )
                                )
                                mainHandler.post {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.download_complete, fileName),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                val reason = if (reasonIndex >= 0) it.getInt(reasonIndex).toString() else "unknown"
                                recordDownloadEvent(
                                    session,
                                    WebDownloadEvent(
                                        status = "failed",
                                        type = "http",
                                        fileName = fileName,
                                        url = url,
                                        mimeType = mimeType,
                                        savedPath = resolvedPath,
                                        downloadId = downloadId,
                                        error = "DownloadManager status=$status reason=$reason"
                                    )
                                )
                                mainHandler.post {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.download_incomplete, fileName),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }.onFailure { error ->
                        recordDownloadEvent(
                            session,
                            WebDownloadEvent(
                                status = "failed",
                                type = "http",
                                fileName = fileName,
                                url = url,
                                mimeType = mimeType,
                                savedPath = destinationFile.absolutePath,
                                downloadId = downloadId,
                                error = error.message ?: "download_complete_receiver_failed"
                            )
                        )
                    }

                    runCatching { context.unregisterReceiver(this) }
                }
            }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun injectDownloadHelper(webView: WebView) {
        val script =
            """
            (function() {
                if (window.__operitDownloadHelperInjected) {
                    return;
                }
                window.__operitDownloadHelperInjected = true;

                function guessName(anchor) {
                    if (!anchor) return "";
                    const raw = String(anchor.getAttribute("download") || "").trim();
                    if (raw) return raw;
                    try {
                        const url = String(anchor.href || "");
                        if (!url) return "";
                        const pathname = new URL(url, location.href).pathname || "";
                        const last = pathname.split("/").filter(Boolean).pop() || "";
                        return decodeURIComponent(last || "");
                    } catch (_) {
                        return "";
                    }
                }

                function downloadBlob(blobUrl, fileName) {
                    fetch(blobUrl)
                        .then((response) => response.blob())
                        .then((blob) => new Promise((resolve, reject) => {
                            const reader = new FileReader();
                            reader.onload = function() {
                                resolve({ data: String(reader.result || ""), type: String(blob.type || "") });
                            };
                            reader.onerror = function() {
                                reject(reader.error || new Error("blob_reader_failed"));
                            };
                            reader.readAsDataURL(blob);
                        }))
                        .then((payload) => {
                            window.OperitWebDownloadBridge.downloadBase64(payload.data, fileName || "", payload.type || "application/octet-stream");
                        })
                        .catch((error) => {
                            window.OperitWebDownloadBridge.log("blob download failed: " + String(error || "unknown"));
                        });
                }

                document.addEventListener("click", function(event) {
                    const anchor = event.target && event.target.closest ? event.target.closest("a[href]") : null;
                    if (!anchor) {
                        return;
                    }
                    const href = String(anchor.href || "");
                    if (!href) {
                        return;
                    }
                    const fileName = guessName(anchor);
                    if (href.startsWith("blob:")) {
                        event.preventDefault();
                        downloadBlob(href, fileName);
                    } else if (href.startsWith("data:")) {
                        event.preventDefault();
                        const mimeType = href.slice(5).split(";")[0] || "application/octet-stream";
                        window.OperitWebDownloadBridge.downloadBase64(href, fileName || "", mimeType);
                    }
                }, true);
            })();
            """.trimIndent()

        runCatching { webView.evaluateJavascript(script, null) }
            .onFailure { AppLogger.w(TAG, "Failed to inject download helper: ${it.message}") }
    }

    private fun injectBlobDownloaderScript(session: WebSession, blobUrl: String) {
        val script =
            """
            (function() {
                fetch(${quoteJs(blobUrl)})
                    .then((response) => response.blob())
                    .then((blob) => new Promise((resolve, reject) => {
                        const reader = new FileReader();
                        reader.onload = function() {
                            resolve({ data: String(reader.result || ""), type: String(blob.type || "") });
                        };
                        reader.onerror = function() {
                            reject(reader.error || new Error("blob_reader_failed"));
                        };
                        reader.readAsDataURL(blob);
                    }))
                    .then((payload) => {
                        window.OperitWebDownloadBridge.downloadBase64(
                            payload.data,
                            "download_${System.currentTimeMillis()}",
                            payload.type || "application/octet-stream"
                        );
                    })
                    .catch((error) => {
                        window.OperitWebDownloadBridge.log("blob downloader script failed: " + String(error || "unknown"));
                    });
            })();
            """.trimIndent()

        runCatching { evaluateJavascriptSync(session.webView, script, DEFAULT_TIMEOUT_MS.coerceIn(2_000L, 8_000L)) }
            .onFailure {
                recordDownloadEvent(
                    session,
                    WebDownloadEvent(
                        status = "failed",
                        type = "blob",
                        fileName = "download_${System.currentTimeMillis()}",
                        url = blobUrl,
                        error = it.message ?: "blob_download_script_failed"
                    )
                )
            }
    }

    private fun handleInlineDownload(
        session: WebSession,
        base64Data: String,
        fileName: String,
        mimeType: String,
        type: String
    ) {
        runCatching {
            val normalizedMimeType = mimeType.ifBlank { guessMimeTypeFromDataUrl(base64Data) }
            val resolvedFileName = resolveInlineDownloadFileName(fileName, normalizedMimeType)
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val targetFile = File(downloadsDir, resolvedFileName)

            val payload = base64Data.substringAfter(',', base64Data)
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            FileOutputStream(targetFile).use { it.write(bytes) }

            recordDownloadEvent(
                session,
                WebDownloadEvent(
                    status = "completed",
                    type = type,
                    fileName = resolvedFileName,
                    mimeType = normalizedMimeType,
                    savedPath = targetFile.absolutePath
                )
            )

            mainHandler.post {
                Toast.makeText(
                    context,
                    context.getString(R.string.download_success, resolvedFileName),
                    Toast.LENGTH_SHORT
                ).show()
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                    data = Uri.fromFile(targetFile)
                })
            }
        }.onFailure { error ->
            val resolvedFileName = resolveInlineDownloadFileName(fileName, mimeType)
            recordDownloadEvent(
                session,
                WebDownloadEvent(
                    status = "failed",
                    type = type,
                    fileName = resolvedFileName,
                    mimeType = mimeType,
                    error = error.message ?: "inline_download_failed"
                )
            )
            mainHandler.post {
                Toast.makeText(
                    context,
                    context.getString(R.string.download_failed, error.message ?: resolvedFileName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun recordDownloadEvent(session: WebSession, event: WebDownloadEvent) {
        session.lastDownloadEvent = event
        session.lastDownloadEventAt = System.currentTimeMillis()
        AppLogger.d(
            TAG,
            "web download event session=${session.id}, status=${event.status}, type=${event.type}, file=${event.fileName}, url=${event.url.orEmpty()}"
        )
    }

    private fun guessMimeTypeFromDataUrl(dataUrl: String): String {
        if (!dataUrl.startsWith("data:")) {
            return "application/octet-stream"
        }
        return dataUrl.substringAfter("data:", "")
            .substringBefore(';', "application/octet-stream")
            .ifBlank { "application/octet-stream" }
    }

    private fun resolveInlineDownloadFileName(fileName: String, mimeType: String): String {
        val trimmed = fileName.trim()
        if (trimmed.isNotBlank()) {
            return sanitizeFileName(trimmed)
        }
        return sanitizeFileName(
            "download_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}${determineExtensionFromMimeType(mimeType)}"
        )
    }

    private fun determineExtensionFromMimeType(mimeType: String): String {
        val lowerMimeType = mimeType.lowercase(Locale.ROOT)
        return when {
            lowerMimeType.startsWith("image/") -> ".${lowerMimeType.substringAfter('/')}"
            lowerMimeType.startsWith("audio/") -> ".${lowerMimeType.substringAfter('/')}"
            lowerMimeType.startsWith("video/") -> ".${lowerMimeType.substringAfter('/')}"
            lowerMimeType.contains("pdf") -> ".pdf"
            lowerMimeType.contains("json") -> ".json"
            lowerMimeType.contains("xml") -> ".xml"
            lowerMimeType.contains("csv") -> ".csv"
            lowerMimeType.contains("zip") -> ".zip"
            lowerMimeType.contains("html") -> ".html"
            lowerMimeType.contains("javascript") -> ".js"
            lowerMimeType.contains("plain") -> ".txt"
            else -> ".bin"
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        val sanitized = fileName.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        return if (sanitized.isBlank()) {
            "download_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        } else {
            sanitized
        }
    }

    private fun getSession(sessionId: String?): WebSession? {
        if (!sessionId.isNullOrBlank()) {
            return sessions[sessionId]
        }

        val activeId = resolvePreferredSessionId()
        if (activeId.isNullOrBlank()) {
            return null
        }
        return sessions[activeId]
    }

    private fun resolvePreferredSessionId(): String? {
        val activeId = activeSessionId

        if (!activeId.isNullOrBlank() && sessions.containsKey(activeId)) {
            return activeId
        }

        val ordered = listSessionIdsInOrder().firstOrNull { sessions.containsKey(it) }
        if (!ordered.isNullOrBlank()) {
            return ordered
        }

        return sessions.keys.firstOrNull()
    }

    private fun addSessionOrder(sessionId: String) {
        synchronized(sessionOrderLock) {
            if (!sessionOrder.contains(sessionId)) {
                sessionOrder.add(sessionId)
            }
        }
    }

    private fun removeSessionOrder(sessionId: String) {
        synchronized(sessionOrderLock) {
            sessionOrder.remove(sessionId)
        }
    }

    private fun listSessionIdsInOrder(): List<String> {
        synchronized(sessionOrderLock) {
            return sessionOrder.toList()
        }
    }

    private fun evaluateJavascriptSync(webView: WebView, script: String, timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        var result: String? = null

        mainHandler.post {
            try {
                if (!webView.isAttachedToWindow) {
                    result = JSONObject.quote("WebView is not attached")
                    latch.countDown()
                    return@post
                }
                webView.evaluateJavascript(script) { value ->
                    result = value
                    latch.countDown()
                }
            } catch (e: Exception) {
                result = JSONObject.quote("JavaScript evaluation error: ${e.message}")
                latch.countDown()
            }
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("JavaScript execution timeout (${timeoutMs}ms)")
        }

        return result ?: "null"
    }

    private fun decodeJsResult(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") {
            return ""
        }

        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return try {
                JSONObject("{\"v\":$raw}").getString("v")
            } catch (_: Exception) {
                raw.substring(1, raw.length - 1)
            }
        }

        return raw
    }

    private fun quoteJs(value: String): String = JSONObject.quote(value)

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }

        return try {
            val json = JSONObject(raw)
            val keys = json.keys()
            val out = mutableMapOf<String, String>()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = json.optString(key, "")
            }
            out
        } catch (e: Exception) {
            AppLogger.w(TAG, "Invalid headers JSON: ${e.message}")
            emptyMap()
        }
    }

    private fun parseStringArrayParam(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val array = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val value = array.opt(i)
                if (value == null || value == JSONObject.NULL) {
                    continue
                }
                out.add(value.toString())
            }
            out
        } catch (e: Exception) {
            AppLogger.w(TAG, "Invalid array JSON: ${e.message}")
            null
        }
    }

    private fun param(tool: AITool, name: String): String? =
        tool.parameters.find { it.name == name }?.value

    private fun boolParam(tool: AITool, name: String, default: Boolean): Boolean {
        return when (param(tool, name)?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }

    private fun intParam(tool: AITool, name: String, default: Int): Int {
        return param(tool, name)?.trim()?.toIntOrNull() ?: default
    }

    private fun longParam(tool: AITool, name: String, default: Long): Long {
        return param(tool, name)?.trim()?.toLongOrNull() ?: default
    }

    private fun ok(toolName: String, payload: JSONObject): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = true,
            result = StringResultData(payload.toString())
        )
    }

    private fun error(toolName: String, message: String): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = false,
            result = StringResultData(""),
            error = message
        )
    }

    private fun <T> runOnMainSync(timeoutMs: Long = 8_000L, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null

        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Main-thread operation timeout (${timeoutMs}ms)")
        }

        if (error != null) {
            throw RuntimeException(error)
        }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
