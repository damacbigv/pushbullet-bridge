package com.example.pushbridge.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.model.NotificationPayload
import com.example.pushbridge.theme.EmeraldGreen
import com.example.pushbridge.theme.GroupPurple
import com.example.pushbridge.theme.PushBridgeThemeTokens
import com.example.pushbridge.ui.components.PushBridgeIcons
import com.example.pushbridge.util.DateTimeUtils

@Composable
fun NotificationsTab(
    notifications: List<NotificationPayload>,
    targetDeviceLabel: String,
    onTriggerTestNotification: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val c = PushBridgeThemeTokens.colors

    var bundledMode by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    val collapsedApps = remember { mutableStateMapOf<String, Boolean>() }
    val replyDrafts = remember { mutableStateMapOf<String, String>() }
    val replyStatuses = remember { mutableStateMapOf<String, Pair<Boolean, String>>() }

    val filtered = remember(notifications, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            notifications
        } else {
            notifications.filter { n ->
                n.appName.lowercase().contains(q) ||
                    n.title.lowercase().contains(q) ||
                    n.text.lowercase().contains(q) ||
                    (n.subText?.lowercase()?.contains(q) == true) ||
                    (n.conversationTitle?.lowercase()?.contains(q) == true)
            }
        }
    }

    val bundledGroups = remember(filtered) {
        filtered
            .groupBy { it.appName.ifBlank { it.packageName.ifBlank { "App" } } }
            .entries
            .sortedByDescending { entry -> entry.value.maxOfOrNull { it.timestamp } ?: 0L }
            .map { it.key to it.value.sortedByDescending { n -> n.timestamp } }
    }

    val handleSendReply: (NotificationPayload) -> Unit = { payload ->
        val draft = replyDrafts[payload.id]?.trim() ?: ""
        if (draft.isNotBlank()) {
            replyStatuses[payload.id] = true to "Sending..."
            PushBridgeFirebaseManager.sendRemoteReplyFromDashboard(
                context = context,
                notificationKey = payload.notificationKey,
                replyText = draft
            ) { ok, msg ->
                if (ok) {
                    replyDrafts[payload.id] = ""
                    replyStatuses[payload.id] = true to (msg ?: "Reply sent")
                } else {
                    replyStatuses[payload.id] = false to (msg ?: "Failed")
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.background)
    ) {
        // Notifications Toolbar
        Surface(
            color = c.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // View Mode Segmented Switch
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = c.surfaceSubtle,
                        border = BorderStroke(1.dp, c.border)
                    ) {
                        Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (bundledMode) c.primary else Color.Transparent,
                                modifier = Modifier.clickable { bundledMode = true }
                            ) {
                                Text(
                                    text = "Bundled by App",
                                    fontSize = 12.sp,
                                    fontWeight = if (bundledMode) FontWeight.Bold else FontWeight.Medium,
                                    color = if (bundledMode) c.onPrimary else c.textMuted,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (!bundledMode) c.primary else Color.Transparent,
                                modifier = Modifier.clickable { bundledMode = false }
                            ) {
                                Text(
                                    text = "Chronological",
                                    fontSize = 12.sp,
                                    fontWeight = if (!bundledMode) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!bundledMode) c.onPrimary else c.textMuted,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${filtered.size} active",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = c.textMuted
                        )
                        if (notifications.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { PushBridgeFirebaseManager.clearAllRemoteNotifications() },
                                border = BorderStroke(1.dp, c.border),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    imageVector = PushBridgeIcons.ClearAll,
                                    contentDescription = "Clear All",
                                    tint = c.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear All", fontSize = 11.sp, color = c.textMuted)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            "Filter notifications by app, sender, or message...",
                            fontSize = 12.sp,
                            color = c.textSubtle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = PushBridgeIcons.Search,
                            contentDescription = "Search",
                            tint = c.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = PushBridgeIcons.Close,
                                    contentDescription = "Clear",
                                    tint = c.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.primary,
                        unfocusedBorderColor = c.border,
                        focusedContainerColor = c.surfaceSubtle,
                        unfocusedContainerColor = c.surfaceSubtle,
                        focusedTextColor = c.textMain,
                        unfocusedTextColor = c.textMain
                    ),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = c.border)

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = c.surface,
                    border = BorderStroke(1.dp, c.border),
                    modifier = Modifier.widthIn(max = 460.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = PushBridgeIcons.Notifications,
                            contentDescription = null,
                            tint = c.accent,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) "No Matching Notifications" else "All Caught Up",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.textMain
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No mirrored alerts matched \"$searchQuery\"."
                            } else {
                                "Live alerts from $targetDeviceLabel appear here automatically with inline Quick Reply support."
                            },
                            fontSize = 12.sp,
                            color = c.textMuted
                        )
                        if (searchQuery.isBlank()) {
                            OutlinedButton(
                                onClick = onTriggerTestNotification,
                                border = BorderStroke(1.dp, c.primary)
                            ) {
                                Text("Post Test Notification", fontSize = 12.sp, color = c.accent)
                            }
                        }
                    }
                }
            }
        } else if (bundledMode) {
            LazyColumn(
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(bundledGroups, key = { it.first }) { (appName, appNotifs) ->
                    val isCollapsed = collapsedApps[appName] ?: false
                    val latestTime = DateTimeUtils.formatRelativeNotificationTime(
                        appNotifs.maxOfOrNull { it.timestamp } ?: 0L
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = c.surface,
                        border = BorderStroke(1.dp, c.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Bundle Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(c.surfaceSubtle)
                                    .clickable { collapsedApps[appName] = !isCollapsed }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(c.primary.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = appName.take(1).uppercase(),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = c.accent
                                        )
                                    }
                                    Text(
                                        text = appName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = c.textMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = c.badgeBg
                                    ) {
                                        Text(
                                            text = "${appNotifs.size}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = c.badgeText,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = latestTime,
                                        fontSize = 11.sp,
                                        color = c.textSubtle
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    TextButton(
                                        onClick = { PushBridgeFirebaseManager.dismissAppNotifications(appName) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Dismiss All", fontSize = 11.sp, color = c.textMuted)
                                    }
                                    Text(
                                        text = if (isCollapsed) "▸" else "▾",
                                        fontSize = 14.sp,
                                        color = c.textMuted,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }

                            if (!isCollapsed) {
                                appNotifs.forEachIndexed { idx, notif ->
                                    HorizontalDivider(thickness = 0.5.dp, color = c.border)
                                    NotificationItemBody(
                                        notif = notif,
                                        showAppPill = false,
                                        replyDraft = replyDrafts[notif.id] ?: "",
                                        onReplyDraftChanged = { replyDrafts[notif.id] = it },
                                        replyStatus = replyStatuses[notif.id],
                                        onSendReply = { handleSendReply(notif) },
                                        onDismiss = { PushBridgeFirebaseManager.dismissRemoteNotification(notif.id) }
                                    )
                                    if (idx < appNotifs.lastIndex) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = c.border.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.id }) { notif ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = c.surface,
                        border = BorderStroke(1.dp, c.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NotificationItemBody(
                            notif = notif,
                            showAppPill = true,
                            replyDraft = replyDrafts[notif.id] ?: "",
                            onReplyDraftChanged = { replyDrafts[notif.id] = it },
                            replyStatus = replyStatuses[notif.id],
                            onSendReply = { handleSendReply(notif) },
                            onDismiss = { PushBridgeFirebaseManager.dismissRemoteNotification(notif.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItemBody(
    notif: NotificationPayload,
    showAppPill: Boolean,
    replyDraft: String,
    onReplyDraftChanged: (String) -> Unit,
    replyStatus: Pair<Boolean, String>?,
    onSendReply: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    val timeLabel = remember(notif.timestamp) {
        DateTimeUtils.formatRelativeNotificationTime(notif.timestamp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (showAppPill) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = c.badgeBg
                    ) {
                        Text(
                            text = notif.appName.ifBlank { "App" },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.badgeText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                if (notif.isGroup) {
                    Surface(shape = RoundedCornerShape(6.dp), color = GroupPurple.copy(alpha = 0.2f)) {
                        Text(
                            text = "Group",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (c.isLight) GroupPurple else Color(0xFFD8B4FE),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (notif.isEncrypted) {
                    Surface(shape = RoundedCornerShape(6.dp), color = EmeraldGreen.copy(alpha = 0.2f)) {
                        Text(
                            text = "🔒 E2EE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (c.isLight) Color(0xFF047857) else Color(0xFF6EE7B7),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (notif.isRcs) {
                    Surface(shape = RoundedCornerShape(6.dp), color = c.badgeBg) {
                        Text(
                            text = "RCS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.badgeText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = timeLabel,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = c.textSubtle
                )
            }

            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = PushBridgeIcons.Close,
                    contentDescription = "Dismiss notification",
                    tint = c.textSubtle,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (notif.title.isNotBlank()) {
            Text(
                text = notif.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = c.textMain
            )
        }

        if (notif.text.isNotBlank()) {
            Text(
                text = notif.text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = c.textMuted
            )
        }

        if (!notif.subText.isNullOrBlank()) {
            Text(
                text = notif.subText,
                fontSize = 11.sp,
                color = c.textSubtle
            )
        }

        if (notif.canReply) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = replyDraft,
                    onValueChange = onReplyDraftChanged,
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "Quick reply to ${notif.title.ifBlank { notif.appName }}...",
                            fontSize = 12.sp,
                            color = c.textSubtle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendReply() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.primary,
                        unfocusedBorderColor = c.border,
                        focusedContainerColor = c.surfaceSubtle,
                        unfocusedContainerColor = c.surfaceSubtle,
                        focusedTextColor = c.textMain,
                        unfocusedTextColor = c.textMain
                    ),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = onSendReply,
                    enabled = replyDraft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.primary,
                        contentColor = c.onPrimary,
                        disabledContainerColor = c.surfaceHover,
                        disabledContentColor = c.textSubtle
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = PushBridgeIcons.Reply,
                        contentDescription = "Reply",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = notif.replyActionTitle?.ifBlank { "Reply" } ?: "Reply",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            replyStatus?.let { (ok, statusText) ->
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    color = if (ok) EmeraldGreen else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Text(
                text = "Notification only",
                fontSize = 10.sp,
                fontStyle = FontStyle.Italic,
                color = c.textSubtle
            )
        }
    }
}
