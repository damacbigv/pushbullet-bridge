package com.example.pushbridge.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.pushbridge.MainActivity
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.model.DeviceRole
import com.example.pushbridge.model.PeerDeviceInfo
import com.example.pushbridge.theme.AmberWarn
import com.example.pushbridge.theme.EmeraldGreen
import com.example.pushbridge.theme.PushBridgePalettes
import com.example.pushbridge.theme.PushBridgeTheme
import com.example.pushbridge.theme.PushBridgeThemeTokens
import com.example.pushbridge.theme.RoseAlert
import com.example.pushbridge.ui.components.PushBridgeIcons
import com.example.pushbridge.ui.dashboard.ClipboardTab
import com.example.pushbridge.ui.dashboard.MessagesTab
import com.example.pushbridge.ui.dashboard.NotificationsTab
import com.example.pushbridge.ui.dashboard.SetupTab
import com.example.pushbridge.util.PermissionUtils
import kotlinx.coroutines.launch

enum class DashboardTab(val label: String) {
    MESSAGES("Messages"),
    NOTIFICATIONS("Notifications"),
    CLIPBOARD("Clipboard"),
    SETTINGS("Settings")
}

@Composable
fun MainScreen(
    onRequestSmsPermission: () -> Unit = {},
    permissionTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTabletLayout = configuration.screenWidthDp >= 600
    val c = PushBridgeThemeTokens.colors
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val deviceId = remember { PushBridgeFirebaseManager.getDeviceId(context) }
    val deviceRole = remember { PushBridgeFirebaseManager.getDeviceRole(context) }

    val hasNotifAccess = remember(permissionTrigger) { PermissionUtils.hasNotificationAccess(context) }
    val hasSmsSend = remember(permissionTrigger) { PermissionUtils.hasSmsSendPermission(context) }
    val hasSmsRead = remember(permissionTrigger) { PermissionUtils.hasSmsReadPermission(context) }
    val hasContactsRead = remember(permissionTrigger) { PermissionUtils.hasContactsReadPermission(context) }

    val selectedThemeId by PushBridgeFirebaseManager.selectedTheme.collectAsState()
    val paginationLimit by PushBridgeFirebaseManager.paginationLimitFlow.collectAsState()
    val mirrorLocalNotifications by PushBridgeFirebaseManager.mirrorLocalNotificationsFlow.collectAsState()
    val peerDevices by PushBridgeFirebaseManager.peerDevices.collectAsState()
    val activeTargetDeviceId by PushBridgeFirebaseManager.activeTargetDeviceId.collectAsState()
    val syncedThreads by PushBridgeFirebaseManager.syncedThreads.collectAsState()
    val syncedNotifications by PushBridgeFirebaseManager.syncedNotifications.collectAsState()
    val syncedContacts by PushBridgeFirebaseManager.syncedContacts.collectAsState()
    val syncedClipboard by PushBridgeFirebaseManager.syncedClipboard.collectAsState()
    val isSyncing by PushBridgeFirebaseManager.isSyncingFromPeer.collectAsState()

    // Tablets always land directly in the Messages workspace; Phones land in Messages once granted
    var activeTab by rememberSaveable {
        mutableStateOf(
            if (deviceRole == DeviceRole.WIFI_TABLET || isTabletLayout || (hasSmsRead && hasNotifAccess)) {
                DashboardTab.MESSAGES
            } else {
                DashboardTab.SETTINGS
            }
        )
    }

    var setupStatusMessage by remember { mutableStateOf<String?>(null) }

    val activePeer = remember(peerDevices, activeTargetDeviceId) {
        peerDevices.firstOrNull { it.deviceId == activeTargetDeviceId }
    }
    val targetOnline = activePeer?.online ?: true
    val targetLabel = activePeer?.deviceName ?: activeTargetDeviceId ?: "Paired Phone"

    val totalUnreadMessages = remember(syncedThreads) {
        syncedThreads.sumOf { it.unreadCount }
    }
    val activeNotificationsCount = syncedNotifications.size

    val triggerSync: (Int) -> Unit = { limit ->
        PushBridgeFirebaseManager.requestPeerSmsSync(context, limit) { _, message ->
            val note = message ?: "Sync initiated."
            setupStatusMessage = note
            coroutineScope.launch {
                snackbarHostState.showSnackbar(note)
            }
        }
    }

    val triggerContactsSync: () -> Unit = {
        PushBridgeFirebaseManager.requestPeerContactsSync(context) { _, message ->
            val note = message ?: "Contacts sync requested."
            setupStatusMessage = note
            coroutineScope.launch {
                snackbarHostState.showSnackbar(note)
            }
        }
    }

    val triggerTestNotification: () -> Unit = {
        postTestNotificationWithReply(context)
        val msg = "Posted test notification with RemoteInput. Check status bar!"
        setupStatusMessage = msg
        coroutineScope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = c.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                DashboardTopBar(
                    isTabletLayout = isTabletLayout,
                    deviceRole = deviceRole,
                    peerDevices = peerDevices,
                    activePeer = activePeer,
                    activeTargetDeviceId = activeTargetDeviceId,
                    targetOnline = targetOnline,
                    selectedThemeId = selectedThemeId,
                    isSyncing = isSyncing,
                    onSelectTargetDevice = { targetId ->
                        PushBridgeFirebaseManager.selectTargetDevice(context, targetId)
                    },
                    onThemeSelected = { themeId ->
                        PushBridgeFirebaseManager.setTheme(context, themeId)
                    },
                    onSyncClicked = { triggerSync(paginationLimit) }
                )

                // Offline Peer Banner (when paired phone hasn't pinged heartbeat recently)
                if (activePeer != null && !targetOnline) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AmberWarn.copy(alpha = 0.14f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "⚠ ${activePeer.deviceName} is offline. Outbound messages will queue until reconnected.",
                            fontSize = 11.sp,
                            color = AmberWarn,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Standby",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AmberWarn
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!isTabletLayout) {
                NavigationBar(
                    containerColor = c.surface,
                    contentColor = c.textMuted,
                    tonalElevation = 0.dp
                ) {
                    DashboardTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        val badgeCount = when (tab) {
                            DashboardTab.MESSAGES -> totalUnreadMessages
                            DashboardTab.NOTIFICATIONS -> activeNotificationsCount
                            else -> 0
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = { activeTab = tab },
                            icon = {
                                TabIconWithBadge(
                                    tab = tab,
                                    badgeCount = badgeCount,
                                    tint = if (selected) c.primary else c.textMuted
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = c.primary,
                                selectedTextColor = c.accent,
                                unselectedIconColor = c.textMuted,
                                unselectedTextColor = c.textMuted,
                                indicatorColor = c.primary.copy(alpha = 0.16f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isTabletLayout) {
                NavigationRail(
                    containerColor = c.surface,
                    contentColor = c.textMuted,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    DashboardTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        val badgeCount = when (tab) {
                            DashboardTab.MESSAGES -> totalUnreadMessages
                            DashboardTab.NOTIFICATIONS -> activeNotificationsCount
                            else -> 0
                        }
                        NavigationRailItem(
                            selected = selected,
                            onClick = { activeTab = tab },
                            icon = {
                                TabIconWithBadge(
                                    tab = tab,
                                    badgeCount = badgeCount,
                                    tint = if (selected) c.primary else c.textMuted
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = c.primary,
                                selectedTextColor = c.accent,
                                unselectedIconColor = c.textMuted,
                                unselectedTextColor = c.textMuted,
                                indicatorColor = c.primary.copy(alpha = 0.16f)
                            )
                        )
                    }
                }
                VerticalDivider(thickness = 1.dp, color = c.border)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when (activeTab) {
                    DashboardTab.MESSAGES -> {
                        MessagesTab(
                            threads = syncedThreads,
                            contacts = syncedContacts,
                            paginationLimit = paginationLimit,
                            isSyncing = isSyncing,
                            isTabletLayout = isTabletLayout,
                            targetOnline = targetOnline,
                            onPaginationChanged = { newLimit -> triggerSync(newLimit) },
                            onSyncRequested = { triggerSync(paginationLimit) }
                        )
                    }

                    DashboardTab.NOTIFICATIONS -> {
                        NotificationsTab(
                            notifications = syncedNotifications,
                            targetDeviceLabel = targetLabel,
                            onTriggerTestNotification = triggerTestNotification
                        )
                    }

                    DashboardTab.CLIPBOARD -> {
                        ClipboardTab(
                            syncedClipboard = syncedClipboard,
                            targetDeviceLabel = targetLabel
                        )
                    }

                    DashboardTab.SETTINGS -> {
                        SetupTab(
                            deviceId = deviceId,
                            deviceRole = deviceRole,
                            peerDevices = peerDevices,
                            activeTargetDeviceId = activeTargetDeviceId,
                            selectedThemeId = selectedThemeId,
                            paginationLimit = paginationLimit,
                            mirrorLocalNotifications = mirrorLocalNotifications,
                            hasNotifAccess = hasNotifAccess,
                            hasSmsRead = hasSmsRead,
                            hasSmsSend = hasSmsSend,
                            hasContactsRead = hasContactsRead,
                            isSyncingThreads = isSyncing,
                            isTabletLayout = isTabletLayout,
                            statusMessage = setupStatusMessage,
                            onSelectTargetDevice = { targetId ->
                                PushBridgeFirebaseManager.selectTargetDevice(context, targetId)
                            },
                            onRefreshDevices = {
                                PushBridgeFirebaseManager.refreshDiscoveredDevices(context)
                            },
                            onThemeSelected = { themeId ->
                                PushBridgeFirebaseManager.setTheme(context, themeId)
                            },
                            onPaginationChanged = { newLimit -> triggerSync(newLimit) },
                            onMirrorLocalNotificationsChanged = { enabled ->
                                PushBridgeFirebaseManager.setMirrorLocalNotifications(context, enabled)
                            },
                            onRequestPermissions = onRequestSmsPermission,
                            onSyncThreads = { triggerSync(paginationLimit) },
                            onSyncContacts = triggerContactsSync,
                            onTriggerTestNotification = triggerTestNotification
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTopBar(
    isTabletLayout: Boolean,
    deviceRole: DeviceRole,
    peerDevices: List<PeerDeviceInfo>,
    activePeer: PeerDeviceInfo?,
    activeTargetDeviceId: String?,
    targetOnline: Boolean,
    selectedThemeId: String,
    isSyncing: Boolean,
    onSelectTargetDevice: (String) -> Unit,
    onThemeSelected: (String) -> Unit,
    onSyncClicked: () -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    var peerMenuExpanded by remember { mutableStateOf(false) }
    var themeMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = c.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Branding & Role Tag
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (targetOnline) EmeraldGreen else AmberWarn)
                    )
                    Text(
                        text = "PushBridge",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textMain
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = c.badgeBg
                    ) {
                        Text(
                            text = "v3.2.0",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.badgeText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (isTabletLayout && deviceRole == DeviceRole.WIFI_TABLET) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF8B5CF6).copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "Tablet Dashboard",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (c.isLight) Color(0xFF6D28D9) else Color(0xFFC4B5FD),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Right Actions: Peer Device Switcher + Theme Picker + Sync
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Target Peer Device Selector
                    Box {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = c.surfaceSubtle,
                            border = BorderStroke(1.dp, c.border),
                            modifier = Modifier.clickable { peerMenuExpanded = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (targetOnline) EmeraldGreen else RoseAlert)
                                )
                                Text(
                                    text = activePeer?.deviceName
                                        ?.removeSuffix(" (Tablet Companion)")
                                        ?: activeTargetDeviceId
                                        ?: "Detecting...",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = c.textMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = if (isTabletLayout) 210.dp else 115.dp)
                                )
                                Icon(
                                    imageVector = PushBridgeIcons.ExpandMore,
                                    contentDescription = "Switch Phone",
                                    tint = c.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = peerMenuExpanded,
                            onDismissRequest = { peerMenuExpanded = false },
                            modifier = Modifier.background(c.surface)
                        ) {
                            if (peerDevices.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Discovering devices...", color = c.textMuted, fontSize = 12.sp) },
                                    onClick = { peerMenuExpanded = false }
                                )
                            } else {
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
                                                    Text(peer.deviceName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textMain)
                                                    Text(peer.deviceId, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = c.textMuted)
                                                }
                                            }
                                        },
                                        trailingIcon = {
                                            if (peer.deviceId == activeTargetDeviceId) {
                                                Icon(PushBridgeIcons.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                                            }
                                        },
                                        onClick = {
                                            peerMenuExpanded = false
                                            onSelectTargetDevice(peer.deviceId)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Quick Theme Picker
                    Box {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = c.surfaceSubtle,
                            border = BorderStroke(1.dp, c.border),
                            modifier = Modifier
                                .size(34.dp)
                                .clickable { themeMenuExpanded = true }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = PushBridgeIcons.Palette,
                                    contentDescription = "Change Theme",
                                    tint = c.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = themeMenuExpanded,
                            onDismissRequest = { themeMenuExpanded = false },
                            modifier = Modifier.background(c.surface)
                        ) {
                            PushBridgePalettes.all.forEach { palette ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(palette.primary)
                                            )
                                            Text(
                                                text = palette.label,
                                                fontSize = 13.sp,
                                                fontWeight = if (palette.id == selectedThemeId) FontWeight.Bold else FontWeight.Normal,
                                                color = c.textMain
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        if (palette.id == selectedThemeId) {
                                            Icon(PushBridgeIcons.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                                        }
                                    },
                                    onClick = {
                                        themeMenuExpanded = false
                                        onThemeSelected(palette.id)
                                    }
                                )
                            }
                        }
                    }

                    // Sync Button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = c.surfaceSubtle,
                        border = BorderStroke(1.dp, c.border),
                        modifier = Modifier
                            .size(34.dp)
                            .clickable(enabled = !isSyncing, onClick = onSyncClicked)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = c.accent
                                )
                            } else {
                                Icon(
                                    imageVector = PushBridgeIcons.Refresh,
                                    contentDescription = "Sync Conversations",
                                    tint = c.textMain,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = c.border)
        }
    }
}

@Composable
private fun TabIconWithBadge(
    tab: DashboardTab,
    badgeCount: Int,
    tint: Color
) {
    val icon = when (tab) {
        DashboardTab.MESSAGES -> PushBridgeIcons.Chat
        DashboardTab.NOTIFICATIONS -> PushBridgeIcons.Notifications
        DashboardTab.CLIPBOARD -> PushBridgeIcons.Clipboard
        DashboardTab.SETTINGS -> PushBridgeIcons.Settings
    }

    if (badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = PushBridgeThemeTokens.colors.primary,
                    contentColor = PushBridgeThemeTokens.colors.onPrimary
                ) {
                    Text(text = if (badgeCount > 99) "99+" else badgeCount.toString(), fontSize = 9.sp)
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
        }
    } else {
        Icon(imageVector = icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun postTestNotificationWithReply(context: Context) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "pushbridge_test_channel"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "PushBridge Test Channel",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
    }

    val remoteInput = RemoteInput.Builder("key_text_reply")
        .setLabel("Type a reply...")
        .build()

    val intent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        context,
        1001,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    val replyAction = NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_send,
        "Reply",
        pendingIntent
    )
        .addRemoteInput(remoteInput)
        .setAllowGeneratedReplies(true)
        .build()

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Alice (Test Sender)")
        .setContentText("Hello! This is a test messaging notification with RemoteInput.")
        .addAction(replyAction)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    notificationManager.notify(9999, notification)
}

@Preview(showBackground = true, widthDp = 960, heightDp = 640)
@Composable
fun MainScreenPreview() {
    PushBridgeTheme {
        MainScreen()
    }
}
