package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

internal enum class WebSessionBrowserSheetRoute {
    NONE,
    TABS,
    MENU,
    HISTORY,
    BOOKMARKS
}

@Immutable
internal data class WebSessionBrowserTab(
    val sessionId: String,
    val title: String,
    val url: String,
    val isActive: Boolean,
    val hasSslError: Boolean
)

@Immutable
internal data class WebSessionSessionHistoryItem(
    val index: Int,
    val title: String,
    val url: String,
    val isCurrent: Boolean
)

@Immutable
internal data class WebSessionBrowserState(
    val activeSessionId: String? = null,
    val pageTitle: String = "",
    val currentUrl: String = "about:blank",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val hasSslError: Boolean = false,
    val isDesktopMode: Boolean = true,
    val tabs: List<WebSessionBrowserTab> = emptyList(),
    val sessionHistory: List<WebSessionSessionHistoryItem> = emptyList()
)

@Immutable
internal data class WebSessionBrowserHostState(
    val browserState: WebSessionBrowserState = WebSessionBrowserState(),
    val sheetRoute: WebSessionBrowserSheetRoute = WebSessionBrowserSheetRoute.NONE,
    val isEditingUrl: Boolean = false,
    val urlDraft: String = WebSessionBrowserState().currentUrl
)

@Serializable
internal data class WebSessionBookmark(
    val url: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
internal data class WebSessionHistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long
)
