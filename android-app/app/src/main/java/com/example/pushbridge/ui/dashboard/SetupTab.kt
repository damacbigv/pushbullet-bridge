package com.example.pushbridge.ui.dashboard

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.data.RcsConversationManager
import com.example.pushbridge.model.DeviceRole
import com.example.pushbridge.model.PeerDeviceInfo
import com.example.pushbridge.service.PushBridgeNotificationListenerService
import com.example.pushbridge.theme.EmeraldGreen
import com.example.pushbridge.theme.GroupPurple
import com.example.pushbridge.theme.PushBridgePalette
import com.example.pushbridge.theme.PushBridgePalettes
import com.example.pushbridge.theme.PushBridgeThemeTokens
import com.example.pushbridge.theme.RoseAlert
import com.example.pushbridge.ui.components.PushBridgeIcons
import com.example.pushbridge.util.PermissionUtils

@Composable
fun SetupTab(
    deviceId: String,
    deviceRole: DeviceRole,
    peerDevices: List<PeerDeviceInfo>,
    activeTargetDeviceId: String?,
    selectedThemeId: String,
    paginationLimit: Int,
    mirrorLocalNotifications: Boolean,
    hasNotifAccess: Boolean,
    hasSmsRead: Boolean,
    hasSmsSend: Boolean,
    hasContactsRead: Boolean,
    isSyncingThreads: Boolean,
    isTabletLayout: Boolean,
    statusMessage: String?,
    onSelectTargetDevice: (String) -> Unit,
    onRefreshDevices: () -> Unit,
    onThemeSelected: (String) -> Unit,
    onPaginationChanged: (Int) -> Unit,
    onMirrorLocalNotificationsChanged: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onSyncThreads: () -> Unit,
    onSyncContacts: () -> Unit,
    onTriggerTestNotification: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val c = PushBridgeThemeTokens.colors
    val isListenerConnected = PushBridgeNotificationListenerService.isConnected

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.background)
            .padding(16.dp)
    ) {
        if (isTabletLayout) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    EngineAndPairingCard(
                        deviceId = deviceId,
                        deviceRole = deviceRole,
                        isListenerConnected = isListenerConnected,
                        peerDevices = peerDevices,
                        activeTargetDeviceId = activeTargetDeviceId,
                        onSelectTargetDevice = onSelectTargetDevice,
                        onRefreshDevices = onRefreshDevices
                    )
                    NotificationMirroringCard(
                        context = context,
                        deviceRole = deviceRole,
                        mirrorEnabled = mirrorLocalNotifications,
                        hasNotifAccess = hasNotifAccess,
                        onMirrorChanged = onMirrorLocalNotificationsChanged
                    )
                    PaginationSelectorCard(
                        currentLimit = paginationLimit,
                        onLimitChanged = onPaginationChanged
                    )
                    DiagnosticsCard(
                        statusMessage = statusMessage,
                        onTriggerTestNotification = onTriggerTestNotification
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    InteractiveThemeSelectorCard(
                        selectedThemeId = selectedThemeId,
                        onThemeSelected = onThemeSelected
                    )
                    SmsAndContactsBridgeCard(
                        hasSmsRead = hasSmsRead,
                        hasSmsSend = hasSmsSend,
                        hasContactsRead = hasContactsRead,
                        isSyncingThreads = isSyncingThreads,
                        isTabletRole = deviceRole == DeviceRole.WIFI_TABLET,
                        onRequestPermissions = onRequestPermissions,
                        onSyncThreads = onSyncThreads,
                        onSyncContacts = onSyncContacts
                    )
                    RcsProtocolCard()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                EngineAndPairingCard(
                    deviceId = deviceId,
                    deviceRole = deviceRole,
                    isListenerConnected = isListenerConnected,
                    peerDevices = peerDevices,
                    activeTargetDeviceId = activeTargetDeviceId,
                    onSelectTargetDevice = onSelectTargetDevice,
                    onRefreshDevices = onRefreshDevices
                )
                InteractiveThemeSelectorCard(
                    selectedThemeId = selectedThemeId,
                    onThemeSelected = onThemeSelected
                )
                NotificationMirroringCard(
                    context = context,
                    deviceRole = deviceRole,
                    mirrorEnabled = mirrorLocalNotifications,
                    hasNotifAccess = hasNotifAccess,
                    onMirrorChanged = onMirrorLocalNotificationsChanged
                )
                PaginationSelectorCard(
                    currentLimit = paginationLimit,
                    onLimitChanged = onPaginationChanged
                )
                SmsAndContactsBridgeCard(
                    hasSmsRead = hasSmsRead,
                    hasSmsSend = hasSmsSend,
                    hasContactsRead = hasContactsRead,
                    isSyncingThreads = isSyncingThreads,
                    isTabletRole = deviceRole == DeviceRole.WIFI_TABLET,
                    onRequestPermissions = onRequestPermissions,
                    onSyncThreads = onSyncThreads,
                    onSyncContacts = onSyncContacts
                )
                RcsProtocolCard()
                DiagnosticsCard(
                    statusMessage = statusMessage,
                    onTriggerTestNotification = onTriggerTestNotification
                )
            }
        }
    }
}

@Composable
private fun EngineAndPairingCard(
    deviceId: String,
    deviceRole: DeviceRole,
    isListenerConnected: Boolean,
    peerDevices: List<PeerDeviceInfo>,
    activeTargetDeviceId: String?,
    onSelectTargetDevice: (String) -> Unit,
    onRefreshDevices: () -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    var dropdownExpanded by remember { mutableStateOf(false) }
    val activePeer = peerDevices.firstOrNull { it.deviceId == activeTargetDeviceId }

    Surface(
        color = c.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("REAL-TIME ENGINE & PEER PAIRING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent)
                TextButton(
                    onClick = onRefreshDevices,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(PushBridgeIcons.Refresh, "Refresh", tint = c.accent, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh", fontSize = 11.sp, color = c.accent)
                }
            }

            InfoRow("Project ID", PushBridgeFirebaseManager.FIREBASE_PROJECT_ID, mono = true)
            InfoRow("This Device ID", deviceId, mono = true)
            InfoRow(
                "This Device Role",
                if (deviceRole == DeviceRole.WIFI_TABLET) "Tablet Companion" else "Primary Telephony Phone",
                valueColor = if (deviceRole == DeviceRole.WIFI_TABLET) GroupPurple else c.accent
            )
            InfoRow(
                "Listener Service",
                if (isListenerConnected) "CONNECTED" else "STANDBY",
                valueColor = if (isListenerConnected) EmeraldGreen else Color(0xFFEAB308)
            )

            HorizontalDivider(thickness = 0.5.dp, color = c.border)

            Text(
                text = "Paired Source Phone (Target for Messages & Notifications):",
                fontSize = 12.sp,
                color = c.textMuted
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = c.surfaceSubtle,
                    border = BorderStroke(1.dp, c.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dropdownExpanded = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (activePeer?.online == true) EmeraldGreen else RoseAlert)
                            )
                            Text(
                                text = activePeer?.let { "${it.deviceName} (${it.deviceId})" }
                                    ?: activeTargetDeviceId
                                    ?: "Discovering devices...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(PushBridgeIcons.ExpandMore, "Select", tint = c.textMuted, modifier = Modifier.size(18.dp))
                    }
                }

                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.background(c.surface)
                ) {
                    peerDevices.forEach { peer ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (peer.online) EmeraldGreen else RoseAlert)
                                    )
                                    Column {
                                        Text(
                                            text = peer.deviceName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.textMain
                                        )
                                        Text(
                                            text = "${peer.deviceId} • ${if (peer.deviceRole == DeviceRole.PRIMARY_PHONE) "Primary Phone" else "Tablet"}",
                                            fontSize = 11.sp,
                                            color = c.textMuted
                                        )
                                    }
                                }
                            },
                            trailingIcon = {
                                if (peer.deviceId == activeTargetDeviceId) {
                                    Icon(PushBridgeIcons.Check, "Selected", tint = c.accent, modifier = Modifier.size(16.dp))
                                }
                            },
                            onClick = {
                                dropdownExpanded = false
                                onSelectTargetDevice(peer.deviceId)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveThemeSelectorCard(
    selectedThemeId: String,
    onThemeSelected: (String) -> Unit
) {
    val c = PushBridgeThemeTokens.colors

    Surface(
        color = c.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(PushBridgeIcons.Palette, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Text("VIBRANT UI THEME SELECTOR (10 THEMES)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent)
                }
                Text("WCAG AAA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
            }

            Text(
                text = "Tap any palette to apply immediately across Messages, Notifications, Clipboard, and Settings:",
                fontSize = 12.sp,
                color = c.textMuted
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PushBridgePalettes.all.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { palette ->
                            ThemeSwatchTile(
                                palette = palette,
                                isSelected = palette.id == selectedThemeId,
                                onClick = { onThemeSelected(palette.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatchTile(
    palette: PushBridgePalette,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.surface,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) palette.primary else palette.border
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.background)
                        .border(1.5.dp, palette.accent, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(palette.primary)
                    )
                }
                Text(
                    text = palette.label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = palette.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = PushBridgeIcons.Check,
                    contentDescription = "Active",
                    tint = palette.accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun NotificationMirroringCard(
    context: Context,
    deviceRole: DeviceRole,
    mirrorEnabled: Boolean,
    hasNotifAccess: Boolean,
    onMirrorChanged: (Boolean) -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    Surface(
        color = c.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("NOTIFICATION LISTENER & MIRRORING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = (if (hasNotifAccess) EmeraldGreen else RoseAlert).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = if (hasNotifAccess) "GRANTED" else "REQUIRED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasNotifAccess) EmeraldGreen else RoseAlert,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surfaceSubtle, RoundedCornerShape(10.dp))
                    .border(1.dp, c.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "Mirror This Device's Notifications",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textMain
                    )
                    Text(
                        text = if (deviceRole == DeviceRole.WIFI_TABLET) {
                            "Off by default on Wi-Fi tablets so Cast & media noise won't upload to Firebase."
                        } else {
                            "Streams incoming alerts and RemoteInput reply actions to your tablet and Chrome."
                        },
                        fontSize = 11.sp,
                        color = c.textMuted
                    )
                }
                Switch(
                    checked = mirrorEnabled,
                    onCheckedChange = onMirrorChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.onPrimary,
                        checkedTrackColor = c.primary
                    )
                )
            }

            Button(
                onClick = { context.startActivity(PermissionUtils.getNotificationAccessSettingsIntent()) },
                colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (hasNotifAccess) "Manage Notification Access" else "Grant Notification Access", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PaginationSelectorCard(
    currentLimit: Int,
    onLimitChanged: (Int) -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    Surface(
        color = c.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SMS THREAD PAGINATION LIMIT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent)
                Text("$currentLimit Threads", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
            }
            Text(
                text = "Controls active conversation history queries synced between your phone, tablet, and Chrome.",
                fontSize = 12.sp,
                color = c.textMuted
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(30, 50, 100).forEach { limit ->
                    val active = currentLimit == limit
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) c.primary else c.surfaceSubtle,
                        border = BorderStroke(1.dp, if (active) c.primary else c.border),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onLimitChanged(limit) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 10.dp)) {
                            Text(
                                text = "$limit Threads",
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) c.onPrimary else c.textMain
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsAndContactsBridgeCard(
    hasSmsRead: Boolean,
    hasSmsSend: Boolean,
    hasContactsRead: Boolean,
    isSyncingThreads: Boolean,
    isTabletRole: Boolean,
    onRequestPermissions: () -> Unit,
    onSyncThreads: () -> Unit,
    onSyncContacts: () -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    Surface(
        color = c.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TELEPHONY & CONTACTS BRIDGE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent)
                val badgeColor = when {
                    isTabletRole -> GroupPurple
                    hasSmsRead && hasSmsSend -> EmeraldGreen
                    else -> RoseAlert
                }
                Surface(shape = RoundedCornerShape(8.dp), color = badgeColor.copy(alpha = 0.2f)) {
                    Text(
                        text = if (isTabletRole) "TABLET PEER SYNC" else if (hasSmsRead && hasSmsSend) "ENABLED" else "ACTION NEEDED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            if (isTabletRole) {
                Text(
                    text = "Wi-Fi Tablet Companion routes outbound SMS/MMS/RCS and contact sync requests transparently through your paired Primary Phone.",
                    fontSize = 12.sp,
                    color = c.textMuted
                )
            } else {
                InfoRow("READ_SMS (MMS & Group)", if (hasSmsRead) "✓ Granted" else "✗ Missing", if (hasSmsRead) EmeraldGreen else RoseAlert)
                InfoRow("SEND_SMS (Broadcast)", if (hasSmsSend) "✓ Granted" else "✗ Missing", if (hasSmsSend) EmeraldGreen else RoseAlert)
                InfoRow("READ_CONTACTS", if (hasContactsRead) "✓ Granted" else "✗ Missing", if (hasContactsRead) EmeraldGreen else RoseAlert)
                OutlinedButton(
                    onClick = onRequestPermissions,
                    border = BorderStroke(1.dp, c.border),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Request Telephony & Contacts Permissions", color = c.textMain)
                }
            }

            Button(
                onClick = onSyncThreads,
                enabled = (hasSmsRead || isTabletRole) && !isSyncingThreads,
                colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSyncingThreads) "Syncing Conversations..." else "Sync Full SMS & Group Threads", fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onSyncContacts,
                enabled = hasContactsRead || isTabletRole,
                colors = ButtonDefaults.buttonColors(containerColor = GroupPurple, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isTabletRole) "Request Contacts Sync from Phone" else "Sync Device Contacts to Firebase", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RcsProtocolCard() {
    val c = PushBridgeThemeTokens.colors
    val rcsThreads = remember { RcsConversationManager.getAllRcsThreads() }
    val e2eeCount = rcsThreads.count { it.isEncrypted }
    val groupRcsCount = rcsThreads.count { it.isGroup }

    Surface(
        color = c.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RCS & E2EE ENCRYPTION SPEC", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                Surface(shape = RoundedCornerShape(6.dp), color = EmeraldGreen.copy(alpha = 0.2f)) {
                    Text(
                        text = "ACTIVE (v3.2)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldGreen,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "Intercepts Google Messages RCS & E2EE chats, strips summary counters, resolves contact URIs cleanly, and merges seamlessly with Telephony group threads.",
                fontSize = 12.sp,
                color = c.textMuted
            )
            InfoRow("Cached RCS Threads", "${rcsThreads.size} ($e2eeCount E2EE, $groupRcsCount groups)", valueColor = EmeraldGreen)
            InfoRow("Reply Architecture", "Native RemoteInput + SMS Fallback", mono = true)
        }
    }
}

@Composable
private fun DiagnosticsCard(
    statusMessage: String?,
    onTriggerTestNotification: () -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    Surface(
        color = c.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("DIAGNOSTICS & TEST", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent)
                Text("PushBridge v3.2.0", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.textSubtle)
            }

            Button(
                onClick = onTriggerTestNotification,
                colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Trigger Test Notification (With RemoteInput)", fontWeight = FontWeight.Bold)
            }

            statusMessage?.let {
                Text(it, color = EmeraldGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = PushBridgeThemeTokens.colors.textMain,
    mono: Boolean = false
) {
    val c = PushBridgeThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = c.textMuted)
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}
