package com.example.pushbridge.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.pushbridge.MainActivity
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.model.DeviceRole
import com.example.pushbridge.service.PushBridgeNotificationListenerService
import com.example.pushbridge.theme.PushBridgeTheme
import com.example.pushbridge.util.DateTimeUtils
import com.example.pushbridge.util.PermissionUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestSmsPermission: () -> Unit = {},
    permissionTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTabletLayout = configuration.screenWidthDp >= 600

    val deviceId = remember { PushBridgeFirebaseManager.getDeviceId(context) }
    val deviceRole = remember { PushBridgeFirebaseManager.getDeviceRole(context) }
    var selectedPaginationLimit by remember { mutableStateOf(PushBridgeFirebaseManager.currentPaginationLimit) }
    var testStatusText by remember { mutableStateOf<String?>(null) }

    // Permissions
    val hasNotifAccess = remember(permissionTrigger) { PermissionUtils.hasNotificationAccess(context) }
    val hasSmsSend = remember(permissionTrigger) { PermissionUtils.hasSmsSendPermission(context) }
    val hasSmsRead = remember(permissionTrigger) { PermissionUtils.hasSmsReadPermission(context) }
    val hasContactsRead = remember(permissionTrigger) { PermissionUtils.hasContactsReadPermission(context) }
    val isListenerConnected = PushBridgeNotificationListenerService.isConnected
    var isSyncingThreads by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (hasNotifAccess || deviceRole == DeviceRole.WIFI_TABLET) Color(0xFF10B981) else Color(0xFFEF4444),
                                    shape = RoundedCornerShape(50)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (deviceRole == DeviceRole.WIFI_TABLET) "PushBridge Tablet Companion" else "PushBridge Phone Companion",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (deviceRole == DeviceRole.WIFI_TABLET) Color(0xFF8B5CF6).copy(alpha = 0.2f) else Color(0xFF0EA5E9).copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (deviceRole == DeviceRole.WIFI_TABLET) Color(0xFF8B5CF6) else Color(0xFF0EA5E9))
                        ) {
                            Text(
                                text = if (deviceRole == DeviceRole.WIFI_TABLET) "Wi-Fi Tablet (Tab S7)" else "Primary Phone (SIM)",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (deviceRole == DeviceRole.WIFI_TABLET) Color(0xFFC4B5FD) else Color(0xFF38BDF8)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        if (isTabletLayout) {
            // Adaptive Two-Column Layout for Tablets (e.g. Galaxy Tab S7)
            Row(
                modifier = modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    EngineStatusCard(deviceId, deviceRole, isListenerConnected)
                    PaginationSelectorCard(
                        currentLimit = selectedPaginationLimit,
                        onLimitChanged = { newLimit ->
                            selectedPaginationLimit = newLimit
                            PushBridgeFirebaseManager.currentPaginationLimit = newLimit
                            isSyncingThreads = true
                            PushBridgeFirebaseManager.syncAllSmsThreads(context, newLimit) { count ->
                                isSyncingThreads = false
                                testStatusText = "Synchronized $count conversation threads (limit: $newLimit)!"
                            }
                        }
                    )
                    NotificationAccessCard(context, hasNotifAccess)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SmsBridgeCard(
                        context = context,
                        hasSmsRead = hasSmsRead,
                        hasSmsSend = hasSmsSend,
                        hasContactsRead = hasContactsRead,
                        isSyncingThreads = isSyncingThreads,
                        isTablet = deviceRole == DeviceRole.WIFI_TABLET,
                        onRequestPermissions = onRequestSmsPermission,
                        onSyncThreads = {
                            isSyncingThreads = true
                            PushBridgeFirebaseManager.syncAllSmsThreads(context, selectedPaginationLimit) { count ->
                                isSyncingThreads = false
                                testStatusText = "Synced $count conversation threads (limit $selectedPaginationLimit) to Firebase!"
                            }
                        },
                        onSyncContacts = {
                            PushBridgeFirebaseManager.syncDeviceContacts(context) { count ->
                                testStatusText = "Synced $count device contacts to Firebase!"
                            }
                        }
                    )
                    ConversationThreadsPreviewCard(context)
                    RcsProtocolCard()
                    ThemeShowcaseCard()
                    DiagnosticsCard(context, testStatusText) { newStatus ->
                        testStatusText = newStatus
                    }
                }
            }
        } else {
            // Standard Single Column Layout for Phones
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                EngineStatusCard(deviceId, deviceRole, isListenerConnected)
                PaginationSelectorCard(
                    currentLimit = selectedPaginationLimit,
                    onLimitChanged = { newLimit ->
                        selectedPaginationLimit = newLimit
                        PushBridgeFirebaseManager.currentPaginationLimit = newLimit
                        isSyncingThreads = true
                        PushBridgeFirebaseManager.syncAllSmsThreads(context, newLimit) { count ->
                            isSyncingThreads = false
                            testStatusText = "Synchronized $count conversation threads (limit: $newLimit)!"
                        }
                    }
                )
                NotificationAccessCard(context, hasNotifAccess)
                SmsBridgeCard(
                    context = context,
                    hasSmsRead = hasSmsRead,
                    hasSmsSend = hasSmsSend,
                    hasContactsRead = hasContactsRead,
                    isSyncingThreads = isSyncingThreads,
                    isTablet = deviceRole == DeviceRole.WIFI_TABLET,
                    onRequestPermissions = onRequestSmsPermission,
                    onSyncThreads = {
                        isSyncingThreads = true
                        PushBridgeFirebaseManager.syncAllSmsThreads(context, selectedPaginationLimit) { count ->
                            isSyncingThreads = false
                            testStatusText = "Synced $count conversation threads (limit $selectedPaginationLimit) to Firebase!"
                        }
                    },
                    onSyncContacts = {
                        PushBridgeFirebaseManager.syncDeviceContacts(context) { count ->
                            testStatusText = "Synced $count device contacts to Firebase!"
                        }
                    }
                )
                ConversationThreadsPreviewCard(context)
                RcsProtocolCard()
                ThemeShowcaseCard()
                DiagnosticsCard(context, testStatusText) { newStatus ->
                    testStatusText = newStatus
                }
            }
        }
    }
}

@Composable
private fun EngineStatusCard(deviceId: String, deviceRole: DeviceRole, isListenerConnected: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("REAL-TIME FIREBASE ENGINE", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Project ID:", style = MaterialTheme.typography.bodyMedium)
                Text(PushBridgeFirebaseManager.FIREBASE_PROJECT_ID, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Device ID:", style = MaterialTheme.typography.bodyMedium)
                Text(deviceId, fontFamily = FontFamily.Monospace)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Role & Platform:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (deviceRole == DeviceRole.WIFI_TABLET) "Tablet Companion" else "Primary Telephony Phone",
                    fontWeight = FontWeight.SemiBold,
                    color = if (deviceRole == DeviceRole.WIFI_TABLET) Color(0xFF8B5CF6) else Color(0xFF0EA5E9)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bridge Service:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (isListenerConnected) "CONNECTED" else "STANDBY",
                    color = if (isListenerConnected) Color(0xFF10B981) else Color(0xFFEAB308),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PaginationSelectorCard(
    currentLimit: Int,
    onLimitChanged: (Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SMS THREAD PAGINATION LIMIT", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9))
                Text("$currentLimit Threads", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
            }
            Text(
                "Scalable pagination dynamically controls active conversation queries across Android, Tablets, and the Chrome Extension.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(30, 50, 100).forEach { limit ->
                    val isSelected = currentLimit == limit
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF0EA5E9) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF0284C7) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onLimitChanged(limit) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "$limit Threads",
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationAccessCard(context: Context, hasNotifAccess: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("NOTIFICATION LISTENER", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9))
                Badge(
                    containerColor = if (hasNotifAccess) Color(0xFF10B981) else Color(0xFFEF4444)
                ) {
                    Text(if (hasNotifAccess) "GRANTED" else "REQUIRED", color = Color.White)
                }
            }
            Text(
                "Allows PushBridge to intercept messaging notifications, extract text, and capture RemoteInput reply actions.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    context.startActivity(PermissionUtils.getNotificationAccessSettingsIntent())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (hasNotifAccess) "Manage Notification Settings" else "Grant Notification Access")
            }
        }
    }
}

@Composable
private fun SmsBridgeCard(
    context: Context,
    hasSmsRead: Boolean,
    hasSmsSend: Boolean,
    hasContactsRead: Boolean,
    isSyncingThreads: Boolean,
    isTablet: Boolean,
    onRequestPermissions: () -> Unit,
    onSyncThreads: () -> Unit,
    onSyncContacts: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TELEPHONY & CONTACTS BRIDGE", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9))
                Badge(
                    containerColor = if (isTablet) Color(0xFF8B5CF6) else if (hasSmsSend && hasSmsRead) Color(0xFF10B981) else Color(0xFFEF4444)
                ) {
                    Text(if (isTablet) "TABLET MODE" else if (hasSmsSend && hasSmsRead) "ENABLED" else "ACTION NEEDED", color = Color.White)
                }
            }

            if (isTablet) {
                Text(
                    "Running on Wi-Fi Tablet Companion. Inbound and outbound SMS/MMS are securely routed via peer-sync with your primary phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC4B5FD)
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("READ_SMS (MMS & Group):", style = MaterialTheme.typography.bodySmall)
                    Text(if (hasSmsRead) "✓ Granted" else "✗ Missing", color = if (hasSmsRead) Color(0xFF10B981) else Color(0xFFEF4444))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SEND_SMS (Broadcast):", style = MaterialTheme.typography.bodySmall)
                    Text(if (hasSmsSend) "✓ Granted" else "✗ Missing", color = if (hasSmsSend) Color(0xFF10B981) else Color(0xFFEF4444))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("READ_CONTACTS:", style = MaterialTheme.typography.bodySmall)
                    Text(if (hasContactsRead) "✓ Granted" else "✗ Missing", color = if (hasContactsRead) Color(0xFF10B981) else Color(0xFFEF4444))
                }
                OutlinedButton(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Request Telephony & Contacts Permissions")
                }
            }

            Button(
                onClick = onSyncThreads,
                enabled = (hasSmsRead || isTablet) && !isSyncingThreads,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSyncingThreads) "Syncing Conversations..." else "Sync Full SMS & Group Threads")
            }

            Button(
                onClick = onSyncContacts,
                enabled = hasContactsRead || isTablet,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
            ) {
                Text("Sync Device Contacts to Firebase")
            }
        }
    }
}

@Composable
private fun ConversationThreadsPreviewCard(context: Context) {
    val sampleThreads = remember {
        listOf(
            Triple("Alice Cooper", "Are we still on for lunch today?", System.currentTimeMillis() - 1000 * 60 * 12),
            Triple("Weekend Hike & BBQ (Group)", "Sarah: Sounds great, count me in!", System.currentTimeMillis() - 1000 * 60 * 5),
            Triple("Bob Martinez", "The server deployment finished cleanly.", System.currentTimeMillis() - 1000 * 60 * 60 * 24 * 3)
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SMS THREADS TIMESTAMP PREVIEW", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF0EA5E9).copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0EA5E9))
                ) {
                    Text(
                        "v3.1.0",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
            }
            Text(
                "Context-aware timestamps: Current calendar date shows time only; older dates display day of week, month, and year.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sampleThreads.forEachIndexed { index, (sender, snippet, timestamp) ->
                    val timeStr = DateTimeUtils.formatThreadTimestamp(timestamp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sender, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(snippet, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0EA5E9).copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0EA5E9).copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = timeStr,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }
                    if (index < sampleThreads.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RcsProtocolCard() {
    val rcsThreads = remember { com.example.pushbridge.data.RcsConversationManager.getAllRcsThreads() }
    val e2eeCount = rcsThreads.count { it.isEncrypted }
    val groupRcsCount = rcsThreads.count { it.isGroup }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RCS & E2EE ENCRYPTION SPEC", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
                ) {
                    Text(
                        "ACTIVE (V3.1)",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6EE7B7)
                    )
                }
            }
            Text(
                "Active MessagingStyle notification hooks intercept Google Messages RCS data packets, end-to-end encrypted chats, and family group conversations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Active RCS Threads:", style = MaterialTheme.typography.bodyMedium)
                Text("${rcsThreads.size} threads (${e2eeCount} E2EE, ${groupRcsCount} groups)", fontWeight = FontWeight.SemiBold, color = Color(0xFF10B981))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RCS Reply Architecture:", style = MaterialTheme.typography.bodyMedium)
                Text("Native RemoteInput RCS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ThemeShowcaseCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("UI THEME ENGINE (10 THEMES)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9))
                Text("WCAG AAA", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF10B981))
            }
            Text(
                "High-contrast vibrant palettes including Cyberpunk Neon, Electric Indigo, Emerald Sunset, Crimson Velvet, and Modern Slate:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            val themes = listOf(
                Triple("Cyberpunk", Color(0xFF050508), Color(0xFF00F0FF)),
                Triple("Indigo", Color(0xFF08081A), Color(0xFF6366F1)),
                Triple("Em-Sunset", Color(0xFF06130E), Color(0xFFF59E0B)),
                Triple("Crimson", Color(0xFF150508), Color(0xFFE11D48)),
                Triple("Slate", Color(0xFF0F141C), Color(0xFF38BDF8))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                themes.forEach { (name, bg, accent) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(bg, RoundedCornerShape(8.dp))
                                .border(1.5.dp, accent, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(accent, RoundedCornerShape(4.dp))
                            )
                        }
                        Text(
                            name,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    context: Context,
    testStatusText: String?,
    onStatusChanged: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DIAGNOSTICS & TEST", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9))

            Button(
                onClick = {
                    postTestNotificationWithReply(context)
                    onStatusChanged("Posted test notification with RemoteInput. Check status bar!")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9))
            ) {
                Text("Trigger Test Notification (With RemoteInput)")
            }

            testStatusText?.let {
                Text(it, color = Color(0xFF10B981), style = MaterialTheme.typography.bodySmall)
            }
        }
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

    val replyLabel = "Type a reply..."
    val remoteInput = RemoteInput.Builder("key_text_reply")
        .setLabel(replyLabel)
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

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    PushBridgeTheme {
        MainScreen()
    }
}
