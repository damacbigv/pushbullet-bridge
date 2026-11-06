package com.example.pushbridge.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.pushbridge.data.PushBridgeSyncManager
import com.example.pushbridge.model.NotificationPayload
import com.example.pushbridge.service.PushBridgeNotificationListenerService
import com.example.pushbridge.theme.PushBridgeTheme
import com.example.pushbridge.util.PermissionUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestSmsPermission: () -> Unit = {},
    permissionTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val deviceId = remember { PushBridgeFirebaseManager.getDeviceId(context) }
    var testStatusText by remember { mutableStateOf<String?>(null) }

    // Re-evaluate permissions whenever trigger changes
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
                                    if (hasNotifAccess) Color(0xFF10B981) else Color(0xFFEF4444),
                                    shape = RoundedCornerShape(50)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PushBridge Companion", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Configuration & Firebase Status
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
                        Text("Service Active:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (isListenerConnected) "CONNECTED" else "STANDBY",
                            color = if (isListenerConnected) Color(0xFF10B981) else Color(0xFFEAB308),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Notification Listener Access Status
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

            // SMS Bridge Status
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
                        Text("SMS PERMISSIONS", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9))
                        Badge(
                            containerColor = if (hasSmsSend && hasSmsRead) Color(0xFF10B981) else Color(0xFFEF4444)
                        ) {
                            Text(if (hasSmsSend && hasSmsRead) "ENABLED" else "ACTION NEEDED", color = Color.White)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("READ_SMS:", style = MaterialTheme.typography.bodySmall)
                        Text(if (hasSmsRead) "✓ Granted" else "✗ Missing", color = if (hasSmsRead) Color(0xFF10B981) else Color(0xFFEF4444))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SEND_SMS:", style = MaterialTheme.typography.bodySmall)
                        Text(if (hasSmsSend) "✓ Granted" else "✗ Missing", color = if (hasSmsSend) Color(0xFF10B981) else Color(0xFFEF4444))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("READ_CONTACTS:", style = MaterialTheme.typography.bodySmall)
                        Text(if (hasContactsRead) "✓ Granted" else "✗ Missing", color = if (hasContactsRead) Color(0xFF10B981) else Color(0xFFEF4444))
                    }
                    OutlinedButton(
                        onClick = onRequestSmsPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Request SMS & Contacts Permissions")
                    }

                    Button(
                        onClick = {
                            isSyncingThreads = true
                            PushBridgeFirebaseManager.syncAllSmsThreads(context) { count ->
                                isSyncingThreads = false
                                testStatusText = "Synced $count conversation threads to Firebase!"
                            }
                        },
                        enabled = hasSmsRead && !isSyncingThreads,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSyncingThreads) "Syncing Conversations..." else "Sync Full SMS & Group Threads")
                    }

                    Button(
                        onClick = {
                            PushBridgeFirebaseManager.syncDeviceContacts(context) { count ->
                                testStatusText = "Synced $count device contacts to Firebase!"
                            }
                        },
                        enabled = hasContactsRead,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                    ) {
                        Text("Sync Device Contacts to Firebase")
                    }
                }
            }

            // Diagnostics & Test Section
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
                            testStatusText = "Posted test notification with RemoteInput. Check status bar!"
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
    }
}

/**
 * Creates and posts a test notification that carries a real RemoteInput reply action
 * so the full interception -> Firebase push -> remote reply cycle can be validated locally.
 */
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
