package com.example.pushbridge.ui.dashboard

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.model.DeviceContactItem
import com.example.pushbridge.model.SmsConversationThread
import com.example.pushbridge.model.SmsMessageItem
import com.example.pushbridge.theme.EmeraldGreen
import com.example.pushbridge.theme.GroupPurple
import com.example.pushbridge.theme.PushBridgePalette
import com.example.pushbridge.theme.PushBridgeThemeTokens
import com.example.pushbridge.ui.components.PushBridgeIcons
import com.example.pushbridge.util.DateTimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessagesTab(
    threads: List<SmsConversationThread>,
    contacts: List<DeviceContactItem>,
    paginationLimit: Int,
    isSyncing: Boolean,
    isTabletLayout: Boolean,
    targetOnline: Boolean,
    onPaginationChanged: (Int) -> Unit,
    onSyncRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val c = PushBridgeThemeTokens.colors

    var searchQuery by remember { mutableStateOf("") }
    var selectedThreadId by remember { mutableStateOf<Long?>(null) }
    var isComposingNewChat by remember { mutableStateOf(false) }
    var newRecipientInput by remember { mutableStateOf("") }
    var composerText by remember { mutableStateOf("") }
    var sendFeedback by remember { mutableStateOf<String?>(null) }
    var lightboxImageData by remember { mutableStateOf<Pair<ImageBitmap, String>?>(null) }

    // Filter threads across display names, addresses, recipients, snippets, and loaded message bodies
    val filteredThreads = remember(threads, searchQuery, contacts) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            threads
        } else {
            val qLower = q.lowercase()
            val terms = qLower.split(Regex("\\s+")).filter { it.isNotBlank() }
            val queryDigits = q.replace(Regex("\\D"), "")

            threads.filter { thread ->
                val title = threadDisplayName(thread, contacts)
                val recipText = thread.recipients.joinToString(" ") { r ->
                    listOfNotNull(r["name"], r["phone"]).joinToString(" ")
                }
                val msgsText = thread.messages.values.joinToString(" ") { raw ->
                    val map = raw as? Map<*, *> ?: return@joinToString ""
                    "${map["body"] ?: ""} ${map["contactName"] ?: ""}"
                }
                val corpus = "$title ${thread.address} ${thread.snippet} $recipText $msgsText".lowercase()
                val textMatch = corpus.contains(qLower) || (terms.isNotEmpty() && terms.all { corpus.contains(it) })
                val digitMatch = queryDigits.length >= 2 &&
                    ("${thread.address} $recipText").replace(Regex("\\D"), "").contains(queryDigits)
                textMatch || digitMatch
            }
        }
    }.take(paginationLimit)

    // Auto-select first thread on tablet layout when idle
    LaunchedEffect(filteredThreads, isTabletLayout, isComposingNewChat) {
        if (isTabletLayout && !isComposingNewChat && filteredThreads.isNotEmpty()) {
            val stillExists = filteredThreads.any { it.threadId == selectedThreadId }
            if (selectedThreadId == null || !stillExists) {
                selectedThreadId = filteredThreads.first().threadId
            }
        }
    }

    val activeThread = remember(threads, selectedThreadId) {
        threads.firstOrNull { it.threadId == selectedThreadId }
    }

    // Handle Android hardware/gesture Back when viewing a chat on compact phones
    BackHandler(enabled = !isTabletLayout && (selectedThreadId != null || isComposingNewChat)) {
        selectedThreadId = null
        isComposingNewChat = false
    }

    val handleSend: () -> Unit = {
        val textToSend = composerText.trim()
        if (textToSend.isNotBlank()) {
            val currentThread = if (isComposingNewChat) null else activeThread
            val recip = if (isComposingNewChat) newRecipientInput.trim() else null
            composerText = ""
            PushBridgeFirebaseManager.sendChatMessage(
                context = context,
                thread = currentThread,
                newRecipientRaw = recip,
                messageBody = textToSend
            ) { success, feedbackMsg ->
                sendFeedback = if (success) {
                    if (isComposingNewChat) {
                        isComposingNewChat = false
                        newRecipientInput = ""
                    }
                    null
                } else {
                    feedbackMsg ?: "Failed to dispatch message"
                }
            }
        }
    }

    if (isTabletLayout) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(c.background)
        ) {
            ThreadListPane(
                threads = filteredThreads,
                allCount = threads.size,
                contacts = contacts,
                selectedThreadId = if (isComposingNewChat) null else selectedThreadId,
                searchQuery = searchQuery,
                paginationLimit = paginationLimit,
                isSyncing = isSyncing,
                onSearchChanged = { searchQuery = it },
                onPaginationChanged = onPaginationChanged,
                onSyncRequested = onSyncRequested,
                onNewChatClick = {
                    isComposingNewChat = true
                    selectedThreadId = null
                    newRecipientInput = ""
                    sendFeedback = null
                },
                onThreadSelected = { thread ->
                    isComposingNewChat = false
                    selectedThreadId = thread.threadId
                    sendFeedback = null
                },
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 380.dp)
                    .fillMaxWidth(0.36f)
                    .fillMaxHeight()
            )

            VerticalDivider(thickness = 1.dp, color = c.border)

            ConversationPane(
                thread = if (isComposingNewChat) null else activeThread,
                isComposingNewChat = isComposingNewChat,
                newRecipientInput = newRecipientInput,
                onNewRecipientChanged = { newRecipientInput = it },
                contacts = contacts,
                composerText = composerText,
                onComposerTextChanged = { composerText = it },
                onSendClick = handleSend,
                sendFeedback = sendFeedback,
                targetOnline = targetOnline,
                showBackButton = false,
                onBackClick = {},
                onSyncRequested = onSyncRequested,
                onImageClick = { bmp, caption -> lightboxImageData = bmp to caption },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    } else {
        // Compact Phone Single-Pane Layout
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(c.background)
        ) {
            if (selectedThreadId == null && !isComposingNewChat) {
                ThreadListPane(
                    threads = filteredThreads,
                    allCount = threads.size,
                    contacts = contacts,
                    selectedThreadId = null,
                    searchQuery = searchQuery,
                    paginationLimit = paginationLimit,
                    isSyncing = isSyncing,
                    onSearchChanged = { searchQuery = it },
                    onPaginationChanged = onPaginationChanged,
                    onSyncRequested = onSyncRequested,
                    onNewChatClick = {
                        isComposingNewChat = true
                        newRecipientInput = ""
                        sendFeedback = null
                    },
                    onThreadSelected = { thread ->
                        isComposingNewChat = false
                        selectedThreadId = thread.threadId
                        sendFeedback = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ConversationPane(
                    thread = if (isComposingNewChat) null else activeThread,
                    isComposingNewChat = isComposingNewChat,
                    newRecipientInput = newRecipientInput,
                    onNewRecipientChanged = { newRecipientInput = it },
                    contacts = contacts,
                    composerText = composerText,
                    onComposerTextChanged = { composerText = it },
                    onSendClick = handleSend,
                    sendFeedback = sendFeedback,
                    targetOnline = targetOnline,
                    showBackButton = true,
                    onBackClick = {
                        selectedThreadId = null
                        isComposingNewChat = false
                    },
                    onSyncRequested = onSyncRequested,
                    onImageClick = { bmp, caption -> lightboxImageData = bmp to caption },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Fullscreen MMS / RCS Image Lightbox Modal
    lightboxImageData?.let { (bitmap, caption) ->
        Dialog(
            onDismissRequest = { lightboxImageData = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { lightboxImageData = null }
                    .padding(16.dp)
            ) {
                IconButton(
                    onClick = { lightboxImageData = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        imageVector = PushBridgeIcons.Close,
                        contentDescription = "Close Image",
                        tint = Color.White
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = caption,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.78f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    if (caption.isNotBlank()) {
                        Text(
                            text = caption,
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadListPane(
    threads: List<SmsConversationThread>,
    allCount: Int,
    contacts: List<DeviceContactItem>,
    selectedThreadId: Long?,
    searchQuery: String,
    paginationLimit: Int,
    isSyncing: Boolean,
    onSearchChanged: (String) -> Unit,
    onPaginationChanged: (Int) -> Unit,
    onSyncRequested: () -> Unit,
    onNewChatClick: () -> Unit,
    onThreadSelected: (SmsConversationThread) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = PushBridgeThemeTokens.colors

    Column(
        modifier = modifier
            .background(c.surfaceSubtle)
    ) {
        // Search + Compose + Sync Toolbar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChanged,
                    singleLine = true,
                    placeholder = {
                        Text(
                            "Search messages & contacts...",
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
                            IconButton(onClick = { onSearchChanged("") }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = PushBridgeIcons.Close,
                                    contentDescription = "Clear search",
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
                    modifier = Modifier.weight(1f)
                )

                FilledIconButton(
                    onClick = onNewChatClick,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = c.primary,
                        contentColor = c.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = PushBridgeIcons.Add,
                        contentDescription = "New Message",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Pagination Strip (30 / 50 / 100) + Refresh Sync
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("SHOW:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSubtle)
                    listOf(30, 50, 100).forEach { limit ->
                        val active = paginationLimit == limit
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (active) c.primary else c.surfaceSubtle,
                            border = BorderStroke(1.dp, if (active) c.primary else c.border),
                            modifier = Modifier.clickable { onPaginationChanged(limit) }
                        ) {
                            Text(
                                text = limit.toString(),
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) c.onPrimary else c.textMuted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onSyncRequested,
                    enabled = !isSyncing,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = c.accent
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Syncing...", fontSize = 11.sp, color = c.accent)
                    } else {
                        Icon(
                            imageVector = PushBridgeIcons.Refresh,
                            contentDescription = "Sync",
                            tint = c.accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
                    }
                }
            }

            if (searchQuery.isNotBlank()) {
                Text(
                    text = "${threads.size} match${if (threads.size == 1) "" else "es"} across $allCount conversations",
                    fontSize = 11.sp,
                    color = c.accent,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = c.border)

        if (threads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No conversations match \"$searchQuery\"" else "No conversations synced yet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textMain
                    )
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "Try clearing the search query or expanding the thread limit."
                        } else {
                            "Tap Sync to pull your latest SMS, MMS, and RCS threads from your paired Android phone."
                        },
                        fontSize = 12.sp,
                        color = c.textMuted
                    )
                    if (searchQuery.isBlank()) {
                        Button(
                            onClick = onSyncRequested,
                            colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
                        ) {
                            Text("Sync from Phone")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(threads, key = { it.threadId }) { thread ->
                    ThreadListRow(
                        thread = thread,
                        contacts = contacts,
                        isSelected = thread.threadId == selectedThreadId,
                        searchQuery = searchQuery,
                        onClick = { onThreadSelected(thread) }
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = c.border.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadListRow(
    thread: SmsConversationThread,
    contacts: List<DeviceContactItem>,
    isSelected: Boolean,
    searchQuery: String,
    onClick: () -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    val displayName = remember(thread, contacts) { threadDisplayName(thread, contacts) }
    val timeLabel = remember(thread.lastTimestamp) { DateTimeUtils.formatThreadTimestamp(thread.lastTimestamp) }
    val initials = remember(displayName) { computeInitials(displayName) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) c.activeItem else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Active Indicator Strip
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isSelected) c.primary else Color.Transparent)
        )

        // Avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (thread.isGroup) {
                        Brush.linearGradient(listOf(GroupPurple, c.primary))
                    } else {
                        Brush.linearGradient(listOf(c.primary, c.accent))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (thread.isGroup) {
                Icon(
                    imageVector = PushBridgeIcons.Group,
                    contentDescription = "Group",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = initials,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Info Column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = highlightQuery(displayName, searchQuery, c),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeLabel,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = c.textSubtle
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (thread.isTyping) {
                    Text(
                        text = thread.typingSender?.let { "$it is typing..." } ?: "Typing...",
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = EmeraldGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = highlightQuery(thread.snippet.ifBlank { "No messages" }, searchQuery, c),
                        fontSize = 12.sp,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (thread.isGroup) {
                    TagPill("Group", GroupPurple.copy(alpha = 0.2f), if (c.isLight) GroupPurple else Color(0xFFD8B4FE))
                }
                if (thread.isEncrypted || thread.protocol == "E2EE_RCS") {
                    TagPill("🔒 E2EE", EmeraldGreen.copy(alpha = 0.2f), if (c.isLight) Color(0xFF047857) else Color(0xFF6EE7B7))
                } else if (thread.isRcs || thread.protocol == "RCS") {
                    TagPill("RCS", c.badgeBg, c.badgeText)
                }

                if (thread.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(c.primary)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = thread.unreadCount.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationPane(
    thread: SmsConversationThread?,
    isComposingNewChat: Boolean,
    newRecipientInput: String,
    onNewRecipientChanged: (String) -> Unit,
    contacts: List<DeviceContactItem>,
    composerText: String,
    onComposerTextChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    sendFeedback: String?,
    targetOnline: Boolean,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onSyncRequested: () -> Unit,
    onImageClick: (ImageBitmap, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = PushBridgeThemeTokens.colors
    val listState = rememberLazyListState()

    val sortedMessages = remember(thread) {
        if (thread == null) emptyList() else PushBridgeFirebaseManager.extractSortedMessages(thread)
    }

    // Scroll to latest message when thread changes or new messages arrive
    LaunchedEffect(thread?.threadId, sortedMessages.size) {
        if (sortedMessages.isNotEmpty()) {
            listState.animateScrollToItem(sortedMessages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .background(c.background)
            .imePadding()
    ) {
        // Active Conversation Header
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showBackButton) {
                        IconButton(onClick = onBackClick, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = PushBridgeIcons.ArrowBack,
                                contentDescription = "Back",
                                tint = c.textMain
                            )
                        }
                    }

                    val headerTitle = when {
                        isComposingNewChat -> "New Conversation"
                        thread != null -> threadDisplayName(thread, contacts)
                        else -> "Select a Conversation"
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isComposingNewChat -> Brush.linearGradient(listOf(c.primary, c.accent))
                                    thread?.isGroup == true -> Brush.linearGradient(listOf(GroupPurple, c.primary))
                                    else -> Brush.linearGradient(listOf(c.primary, c.accent))
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isComposingNewChat -> Text("+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            thread?.isGroup == true -> Icon(PushBridgeIcons.Group, "Group", tint = Color.White, modifier = Modifier.size(18.dp))
                            thread != null -> Text(computeInitials(headerTitle), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            else -> Icon(PushBridgeIcons.Chat, "Chat", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = headerTitle,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.textMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = when {
                            isComposingNewChat -> "Enter a contact name or comma-separated phone numbers"
                            thread != null && thread.isGroup -> {
                                val participantCount = maxOf(thread.recipients.size, 2)
                                "Group Chat • $participantCount participants"
                            }
                            thread != null -> thread.address
                            else -> "Choose a conversation from the left pane"
                        }
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (thread != null && !isComposingNewChat) {
                        when {
                            thread.isEncrypted || thread.protocol == "E2EE_RCS" -> {
                                TagPill("🔒 E2EE RCS", EmeraldGreen.copy(alpha = 0.2f), if (c.isLight) Color(0xFF047857) else Color(0xFF6EE7B7))
                            }
                            thread.isRcs || thread.protocol == "RCS" -> {
                                TagPill("RCS Active", c.badgeBg, c.badgeText)
                            }
                            else -> {
                                TagPill("Android SMS", c.surfaceHover, c.textMuted)
                            }
                        }
                    }
                }

                // Group Participant Chips
                if (!isComposingNewChat && thread != null && thread.isGroup && thread.recipients.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        thread.recipients.take(10).forEach { recip ->
                            val chipName = resolveRecipientName(recip, contacts)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = c.surfaceSubtle,
                                border = BorderStroke(1.dp, c.border)
                            ) {
                                Text(
                                    text = "• $chipName",
                                    fontSize = 11.sp,
                                    color = c.textMuted,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = c.border)
            }
        }

        // New Chat Recipient Bar & Contact Autocomplete
        if (isComposingNewChat) {
            val matchingContacts = remember(newRecipientInput, contacts) {
                val token = newRecipientInput.substringAfterLast(",").trim().lowercase()
                if (token.isBlank()) {
                    emptyList()
                } else {
                    contacts.filter {
                        it.name.lowercase().contains(token) ||
                            it.phone.lowercase().contains(token) ||
                            it.cleanDigits.contains(token)
                    }.take(6)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surfaceSubtle)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = newRecipientInput,
                    onValueChange = onNewRecipientChanged,
                    singleLine = true,
                    label = { Text("To", color = c.textMuted) },
                    placeholder = { Text("Name, phone, or +1555..., +1555...", color = c.textSubtle, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.primary,
                        unfocusedBorderColor = c.border,
                        focusedContainerColor = c.surface,
                        unfocusedContainerColor = c.surface,
                        focusedTextColor = c.textMain,
                        unfocusedTextColor = c.textMain
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (matchingContacts.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = c.surface,
                        border = BorderStroke(1.dp, c.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            matchingContacts.forEachIndexed { idx, contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val prefix = if (newRecipientInput.contains(",")) {
                                                newRecipientInput.substringBeforeLast(",") + ", "
                                            } else ""
                                            onNewRecipientChanged(prefix + contact.phone)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(contact.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textMain)
                                    Text(contact.phone, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.textMuted)
                                }
                                if (idx < matchingContacts.lastIndex) {
                                    HorizontalDivider(thickness = 0.5.dp, color = c.border)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = c.border)
        }

        // Group Broadcast Banner
        if (!isComposingNewChat && thread != null && thread.isGroup) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GroupPurple.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(PushBridgeIcons.Group, "Broadcast", tint = if (c.isLight) GroupPurple else Color(0xFFD8B4FE), modifier = Modifier.size(14.dp))
                val pCount = maxOf(thread.recipients.size, 2)
                Text(
                    text = "Group Conversation: Outbound replies broadcast to all $pCount participants.",
                    fontSize = 11.sp,
                    color = if (c.isLight) c.textMain else Color(0xFFE9D5FF)
                )
            }
        }

        // Timeline Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                isComposingNewChat -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(PushBridgeIcons.Chat, null, tint = c.textSubtle, modifier = Modifier.size(36.dp))
                        Text("Start a New Message", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textMain)
                        Text(
                            "Select a synced contact above or enter phone numbers separated by commas for a group text.",
                            fontSize = 12.sp,
                            color = c.textMuted
                        )
                    }
                }
                thread == null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(PushBridgeIcons.Chat, null, tint = c.textSubtle, modifier = Modifier.size(40.dp))
                        Text("Ready to Text Across Devices", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.textMain)
                        Text(
                            "Pick any conversation or tap Sync to fetch recent SMS, MMS, and RCS threads from your phone.",
                            fontSize = 12.sp,
                            color = c.textMuted
                        )
                        Button(
                            onClick = onSyncRequested,
                            colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
                        ) {
                            Text("Sync Conversations")
                        }
                    }
                }
                sortedMessages.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No messages loaded in this thread yet.",
                            fontSize = 13.sp,
                            color = c.textSubtle
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = sortedMessages,
                            key = { idx, item -> "${item.id}_${idx}" }
                        ) { idx, msg ->
                            val currentDateLabel = DateTimeUtils.formatDateDivider(msg.timestamp)
                            val prevDateLabel = if (idx > 0) {
                                DateTimeUtils.formatDateDivider(sortedMessages[idx - 1].timestamp)
                            } else ""

                            if (currentDateLabel.isNotBlank() && currentDateLabel != prevDateLabel) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = c.surface,
                                        border = BorderStroke(1.dp, c.border)
                                    ) {
                                        Text(
                                            text = currentDateLabel,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.textMuted,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            ChatMessageBubble(
                                msg = msg,
                                isGroup = thread.isGroup,
                                contacts = contacts,
                                onImageClick = onImageClick
                            )
                        }
                    }
                }
            }
        }

        // Send Status Feedback Bar
        sendFeedback?.let { err ->
            Text(
                text = err,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        // Message Composer Bar
        if (thread != null || isComposingNewChat) {
            HorizontalDivider(thickness = 1.dp, color = c.border)
            Surface(
                color = c.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!targetOnline) {
                        Text(
                            text = "Phone appears offline — message will queue for delivery on reconnect.",
                            fontSize = 11.sp,
                            color = Color(0xFFF59E0B),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = composerText,
                            onValueChange = onComposerTextChanged,
                            placeholder = {
                                val hint = if (thread?.isGroup == true) {
                                    "Broadcast message to group..."
                                } else {
                                    "Type a text message..."
                                }
                                Text(hint, fontSize = 13.sp, color = c.textSubtle)
                            },
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { onSendClick() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = c.primary,
                                unfocusedBorderColor = c.border,
                                focusedContainerColor = c.surfaceSubtle,
                                unfocusedContainerColor = c.surfaceSubtle,
                                focusedTextColor = c.textMain,
                                unfocusedTextColor = c.textMain
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = onSendClick,
                            enabled = composerText.isNotBlank() && (!isComposingNewChat || newRecipientInput.isNotBlank()),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.primary,
                                contentColor = c.onPrimary,
                                disabledContainerColor = c.surfaceHover,
                                disabledContentColor = c.textSubtle
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Icon(
                                imageVector = PushBridgeIcons.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    msg: SmsMessageItem,
    isGroup: Boolean,
    contacts: List<DeviceContactItem>,
    onImageClick: (ImageBitmap, String) -> Unit
) {
    val c = PushBridgeThemeTokens.colors
    val isIncoming = msg.isIncoming
    val bubbleBg = if (isIncoming) c.msgRecv else c.msgSent
    val bubbleFg = if (isIncoming) c.msgRecvText else c.msgSentText
    val bubbleShape = if (isIncoming) {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    }

    // Decode Base64 MMS attachment off UI thread
    val mediaRaw = msg.attachmentData
    val decodedBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = msg.id, key2 = mediaRaw) {
        value = if (!mediaRaw.isNullOrBlank()) {
            withContext(Dispatchers.Default) {
                decodeBase64Image(mediaRaw)
            }
        } else null
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isIncoming) Alignment.Start else Alignment.End
    ) {
        Surface(
            color = bubbleBg,
            shape = bubbleShape,
            border = if (isIncoming) BorderStroke(1.dp, c.border) else null,
            shadowElevation = if (isIncoming) 0.dp else 2.dp,
            modifier = Modifier.widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isIncoming && isGroup) {
                    val senderDisplay = resolveBubbleSenderName(msg, contacts)
                    if (senderDisplay.isNotBlank()) {
                        Text(
                            text = "• $senderDisplay",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (c.isLight) c.primary else c.accent
                        )
                    }
                }

                if (msg.body.isNotBlank()) {
                    val linkColor = if (isIncoming) c.accent else bubbleFg
                    Text(
                        text = buildLinkifiedText(msg.body, linkColor),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = bubbleFg
                    )
                }

                decodedBitmap?.let { bmp ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, c.border, RoundedCornerShape(10.dp))
                            .clickable { onImageClick(bmp, msg.body) }
                    ) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "MMS Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 220.dp)
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                        ) {
                            Text(
                                text = "Tap to expand",
                                fontSize = 10.sp,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Metadata Footer
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = DateTimeUtils.formatMessageTime(msg.timestamp),
                fontSize = 10.sp,
                color = c.textSubtle
            )
            if (msg.isEncrypted || msg.protocol == "E2EE_RCS") {
                Text(text = "🔒", fontSize = 10.sp)
            } else if (msg.isRcs || msg.protocol == "RCS") {
                Text(text = "RCS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.accent)
            }
            if (!isIncoming) {
                Text(
                    text = if (msg.status == "sending") "Sending..." else "✓✓",
                    fontSize = 10.sp,
                    color = if (msg.status == "sending") c.textMuted else c.accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TagPill(text: String, bg: Color, fg: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

private fun decodeBase64Image(raw: String): ImageBitmap? {
    return try {
        val clean = raw.substringAfter("base64,", raw).trim()
        if (clean.isBlank() || clean.startsWith("<svg", ignoreCase = true)) return null
        val bytes = Base64.decode(clean, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

private fun buildLinkifiedText(text: String, linkColor: Color): AnnotatedString {
    val urlRegex = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        append(text)
        urlRegex.findAll(text).forEach { match ->
            addLink(
                url = LinkAnnotation.Url(
                    url = match.value,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    )
                ),
                start = match.range.first,
                end = match.range.last + 1
            )
        }
    }
}

private fun highlightQuery(text: String, query: String, c: PushBridgePalette): AnnotatedString {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val lowerText = text.lowercase()
        val lowerQuery = trimmed.lowercase()
        var startIndex = 0
        while (true) {
            val found = lowerText.indexOf(lowerQuery, startIndex)
            if (found < 0) break
            addStyle(
                style = SpanStyle(
                    background = c.accent.copy(alpha = 0.25f),
                    color = c.textMain,
                    fontWeight = FontWeight.Bold
                ),
                start = found,
                end = found + lowerQuery.length
            )
            startIndex = found + lowerQuery.length
        }
    }
}

private fun threadDisplayName(
    thread: SmsConversationThread,
    contacts: List<DeviceContactItem>
): String {
    if (!thread.groupTitle.isNullOrBlank()) return thread.groupTitle
    if (!thread.contactName.isNullOrBlank()) return thread.contactName
    if (thread.isGroup && thread.recipients.isNotEmpty()) {
        return thread.recipients.joinToString(", ") { resolveRecipientName(it, contacts) }
    }
    val digits = thread.address.replace(Regex("\\D"), "")
    if (digits.length >= 7) {
        val last10 = digits.takeLast(10)
        contacts.firstOrNull { it.cleanDigits.takeLast(10) == last10 }?.let { return it.name }
    }
    return thread.address.ifBlank { "Conversation ${thread.threadId}" }
}

private fun resolveRecipientName(
    recip: Map<String, String>,
    contacts: List<DeviceContactItem>
): String {
    val rawName = recip["name"] ?: ""
    val phone = recip["phone"] ?: ""
    if (rawName.isNotBlank() && rawName.any { it.isLetter() }) return rawName
    val digits = (recip["cleanDigits"] ?: phone.replace(Regex("\\D"), "")).takeLast(10)
    if (digits.length >= 7) {
        contacts.firstOrNull { it.cleanDigits.takeLast(10) == digits }?.let { return it.name }
    }
    return rawName.ifBlank { phone.ifBlank { "Participant" } }
}

private fun resolveBubbleSenderName(
    msg: SmsMessageItem,
    contacts: List<DeviceContactItem>
): String {
    if (!msg.contactName.isNullOrBlank() && msg.contactName.any { it.isLetter() }) {
        return msg.contactName
    }
    val digits = msg.address.replace(Regex("\\D"), "").takeLast(10)
    if (digits.length >= 7) {
        contacts.firstOrNull { it.cleanDigits.takeLast(10) == digits }?.let { return it.name }
    }
    return msg.contactName ?: msg.address
}

private fun computeInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}
