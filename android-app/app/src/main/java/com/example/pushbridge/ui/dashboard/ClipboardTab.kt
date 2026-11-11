package com.example.pushbridge.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.model.ClipboardItem
import com.example.pushbridge.theme.EmeraldGreen
import com.example.pushbridge.theme.PushBridgeThemeTokens
import com.example.pushbridge.ui.components.PushBridgeIcons
import com.example.pushbridge.util.DateTimeUtils

@Composable
fun ClipboardTab(
    syncedClipboard: ClipboardItem?,
    targetDeviceLabel: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val c = PushBridgeThemeTokens.colors
    val sysClipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    var inputDraft by remember { mutableStateOf("") }
    var statusFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: Push Local / Custom Text to Paired Devices
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.surface,
                border = BorderStroke(1.dp, c.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = PushBridgeIcons.Clipboard,
                            contentDescription = null,
                            tint = c.accent,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Universal Clipboard Sync",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.textMain
                            )
                            Text(
                                text = "Push text, links, or snippets instantaneously to $targetDeviceLabel and Chrome.",
                                fontSize = 12.sp,
                                color = c.textMuted
                            )
                        }
                    }

                    OutlinedTextField(
                        value = inputDraft,
                        onValueChange = {
                            inputDraft = it
                            statusFeedback = null
                        },
                        placeholder = {
                            Text(
                                "Paste or type text to sync across all connected devices...",
                                fontSize = 13.sp,
                                color = c.textSubtle
                            )
                        },
                        minLines = 3,
                        maxLines = 7,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = c.primary,
                            unfocusedBorderColor = c.border,
                            focusedContainerColor = c.surfaceSubtle,
                            unfocusedContainerColor = c.surfaceSubtle,
                            focusedTextColor = c.textMain,
                            unfocusedTextColor = c.textMain
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipText = sysClipboard.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                if (!clipText.isNullOrBlank()) {
                                    inputDraft = clipText
                                    statusFeedback = true to "Loaded text from local device clipboard."
                                } else {
                                    statusFeedback = false to "Local device clipboard is empty."
                                }
                            },
                            border = BorderStroke(1.dp, c.border),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = PushBridgeIcons.Copy,
                                contentDescription = "Paste Local",
                                tint = c.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Paste Local", fontSize = 12.sp, color = c.textMain)
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Button(
                            onClick = {
                                val text = inputDraft.trim()
                                if (text.isNotBlank()) {
                                    PushBridgeFirebaseManager.pushClipboardText(context, text) { ok ->
                                        statusFeedback = if (ok) {
                                            true to "Clipboard synced to Firebase & Chrome!"
                                        } else {
                                            false to "Failed syncing clipboard to Firebase."
                                        }
                                    }
                                }
                            },
                            enabled = inputDraft.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.primary,
                                contentColor = c.onPrimary,
                                disabledContainerColor = c.surfaceHover,
                                disabledContentColor = c.textSubtle
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = PushBridgeIcons.Send,
                                contentDescription = "Push",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Push to Devices", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    statusFeedback?.let { (ok, msg) ->
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (ok) EmeraldGreen else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Card 2: Latest Synced Clipboard Received from Firebase
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.surface,
                border = BorderStroke(1.dp, c.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "LATEST CLOUD CLIPBOARD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.accent
                        )
                        syncedClipboard?.let { item ->
                            val timeStr = DateTimeUtils.formatRelativeNotificationTime(item.timestamp)
                            val sourceStr = item.source.ifBlank { targetDeviceLabel }
                            Text(
                                text = "$sourceStr • $timeStr",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = c.textSubtle
                            )
                        }
                    }

                    if (syncedClipboard == null || syncedClipboard.text.isBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = c.surfaceSubtle,
                            border = BorderStroke(1.dp, c.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No synced clipboard content yet. Copy from Chrome extension or push text above.",
                                fontSize = 12.sp,
                                color = c.textMuted,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = c.surfaceSubtle,
                            border = BorderStroke(1.dp, c.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = syncedClipboard.text,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = c.textMain,
                                modifier = Modifier.padding(14.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    val clip = ClipData.newPlainText("PushBridge Clipboard", syncedClipboard.text)
                                    sysClipboard.setPrimaryClip(clip)
                                    statusFeedback = true to "Copied cloud clipboard text to this device!"
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = c.badgeBg,
                                    contentColor = c.badgeText
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = PushBridgeIcons.Copy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy to Device Clipboard", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
