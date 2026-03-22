package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun WebSessionTopUrlBar(
    url: String,
    pageTitle: String,
    isLoading: Boolean,
    isEditing: Boolean,
    urlDraft: String,
    isBookmarked: Boolean,
    tabCount: Int,
    onStartEditing: () -> Unit,
    onUrlDraftChange: (String) -> Unit,
    onSubmitUrl: () -> Unit,
    onStopEditing: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border =
                        BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = tabCount.coerceAtLeast(1).toString(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border =
                        BorderStroke(
                            width = 1.dp,
                            color =
                                if (isEditing) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                                }
                        )
                ) {
                    if (isEditing) {
                        OutlinedTextField(
                            value = urlDraft,
                            onValueChange = onUrlDraftChange,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(20.dp),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            placeholder = {
                                Text(
                                    text = url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions =
                                KeyboardActions(
                                    onGo = {
                                        onSubmitUrl()
                                    }
                                ),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                ),
                            trailingIcon = {
                                IconButton(onClick = onSubmitUrl) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowForward,
                                        contentDescription = null
                                    )
                                }
                            }
                        )
                    } else {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onStartEditing)
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector =
                                    if (url.startsWith("https://")) {
                                        Icons.Filled.Lock
                                    } else {
                                        Icons.Filled.Language
                                    },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = url.ifBlank { pageTitle.ifBlank { "about:blank" } },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            IconButton(
                                modifier = Modifier.size(26.dp),
                                onClick = {
                                    onStopEditing()
                                    onToggleBookmark()
                                }
                            ) {
                                Icon(
                                    imageVector =
                                        if (isBookmarked) {
                                            Icons.Filled.Star
                                        } else {
                                            Icons.Outlined.StarOutline
                                        },
                                    contentDescription = null,
                                    tint =
                                        if (isBookmarked) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border =
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            )
                    ) {
                        IconButton(onClick = onRefreshOrStop) {
                            Icon(
                                imageVector =
                                    if (isLoading) {
                                        Icons.Filled.Close
                                    } else {
                                        Icons.Filled.Refresh
                                    },
                                contentDescription = null
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border =
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            )
                    ) {
                        IconButton(onClick = onMinimize) {
                            Icon(
                                imageVector = Icons.Filled.Remove,
                                contentDescription = null
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier =
                        Modifier
                            .padding(start = 44.dp)
                            .width(72.dp)
                            .height(2.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(999.dp)
                            )
                )
            }
        }
    }
}
