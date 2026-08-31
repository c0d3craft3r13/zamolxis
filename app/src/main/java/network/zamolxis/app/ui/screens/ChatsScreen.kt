package network.zamolxis.app.ui.screens

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import network.zamolxis.app.R
import network.zamolxis.app.data.db.entity.GroupEntity
import network.zamolxis.app.data.repository.Conversation
import network.zamolxis.app.service.MeshReachability
import network.zamolxis.app.service.SyncResult
import network.zamolxis.app.ui.components.ProfileIcon
import network.zamolxis.app.ui.components.SearchableTopAppBar
import network.zamolxis.app.ui.components.StarToggleButton
import network.zamolxis.app.ui.components.SyncStatusBottomSheet
import network.zamolxis.app.ui.components.meshStatusText
import network.zamolxis.app.ui.components.simpleVerticalScrollbar
import network.zamolxis.app.ui.screens.settings.AudienceProfile
import network.zamolxis.app.ui.util.rememberLifecycleTickerMillis
import network.zamolxis.app.viewmodel.ChatsViewModel
import network.zamolxis.app.viewmodel.ChatsSegment
import network.zamolxis.app.viewmodel.ChatListItem
import network.zamolxis.app.viewmodel.ContactToggleResult
import network.zamolxis.app.viewmodel.SharedImageViewModel
import network.zamolxis.app.viewmodel.SharedTextViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How often the connection line re-evaluates while the screen is on. The freshness
 * window is five minutes, so this only has to be fine enough that the wording does
 * not visibly lag.
 */
private const val MESH_STATUS_TICK_MS = 15_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onChatClick: (peerHash: String, peerName: String) -> Unit = { _, _ -> },
    onCallHistoryClick: (callAttemptId: String) -> Unit = {},
    onActiveCallHistoryClick: (
        callAttemptId: String,
        localIdentityHash: String,
        remoteIdentityHash: String,
        profileCode: Int,
    ) -> Unit = { _, _, _, _ -> },
    onViewPeerDetails: (peerHash: String) -> Unit = {},
    onLocateOnMap: (peerHash: String) -> Unit = {},
    onNavigateToQrScanner: () -> Unit = {},
    onGroupClick: (groupId: String) -> Unit = {},
    onNewGroupClick: () -> Unit = {},
    viewModel: ChatsViewModel = hiltViewModel(),
    settingsViewModel: network.zamolxis.app.viewmodel.SettingsViewModel = hiltViewModel(),
    debugViewModel: network.zamolxis.app.viewmodel.DebugViewModel = hiltViewModel(),
    // Data, not a view model. A `hiltViewModel()` default is evaluated whether or not the
    // build shows the status line, which needs a Hilt-aware Activity that the Compose
    // tests do not have. The composition root supplies the real flow; the default keeps
    // this screen constructible from a plain test host.
    meshReachability: StateFlow<MeshReachability> = MutableStateFlow(MeshReachability()),
) {
    val reachability by meshReachability.collectAsState()

    // The status line goes stale on its own rather than on a background timer: this
    // ticks only while the screen is on, and only in the build that shows the line.
    //
    // The ticker is used as a recomposition pulse, not as the clock. It reports
    // System.currentTimeMillis(), and MeshReachability timestamps are
    // SystemClock.elapsedRealtime() — comparing the two would be meaningless, and a
    // wall-clock jump (NTP, the user changing the time) would age the line by decades.
    val meshStatusPulse =
        rememberLifecycleTickerMillis(
            periodMs = MESH_STATUS_TICK_MS,
            enabled = AudienceProfile.isSimpleUi,
        )
    val meshStatusTickMs = remember(meshStatusPulse) { SystemClock.elapsedRealtime() }

    val chatsState by viewModel.chatsState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val voiceSearchQuery by viewModel.voiceSearchQuery.collectAsState()
    val selectedSegment by viewModel.selectedSegment.collectAsState()
    val voiceHistoryState by viewModel.voiceHistoryState.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val draftsMap by viewModel.draftsMap.collectAsState()
    val isTransportEnabled by viewModel.isTransportEnabled.collectAsState()
    var isSearching by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val sharedTextViewModel: SharedTextViewModel = viewModel(viewModelStoreOwner = context as androidx.activity.ComponentActivity)
    val sharedImageViewModel: SharedImageViewModel = viewModel(viewModelStoreOwner = context as androidx.activity.ComponentActivity)

    val listState = rememberLazyListState()
    val voiceListState = rememberLazyListState()

    // Hoist shared-content state above LazyColumn so it's collected once at screen level
    // rather than per-item inside the items{} lambda (avoids redundant subscriptions).
    val pendingSharedText by sharedTextViewModel.sharedText.collectAsStateWithLifecycle()
    val pendingSharedImages by sharedImageViewModel.sharedImages.collectAsStateWithLifecycle()

    // Delete/Block dialog state (context menu state is now per-card)
    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }
    var groupToLeave by remember { mutableStateOf<GroupEntity?>(null) }
    var groupToDelete by remember { mutableStateOf<GroupEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showVoiceActions by remember { mutableStateOf(false) }
    var showClearCallHistoryDialog by remember { mutableStateOf(false) }

    // Sync status bottom sheet state
    var showSyncStatusSheet by remember { mutableStateOf(false) }
    val syncStatusSheetState = rememberModalBottomSheetState()

    // QR code state
    var showQrBottomSheet by remember { mutableStateOf(false) }
    var showQrCodeDialog by remember { mutableStateOf(false) }
    val qrCodeData by debugViewModel.qrCodeData.collectAsState()
    val identityHash by debugViewModel.identityHash.collectAsState()
    val destinationHash by debugViewModel.destinationHash.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.callHistoryNavigation.collect { destination ->
            when (destination) {
                is network.zamolxis.app.viewmodel.CallHistoryNavigation.Details ->
                    onCallHistoryClick(destination.callAttemptId)
                is network.zamolxis.app.viewmodel.CallHistoryNavigation.ActiveCall ->
                    onActiveCallHistoryClick(
                        destination.callAttemptId,
                        destination.localIdentityHash,
                        destination.remoteIdentityHash,
                        destination.profileCode,
                    )
            }
        }
    }

    // Observe manual sync results and show Toast
    LaunchedEffect(Unit) {
        viewModel.manualSyncResult.collect { result ->
            val message =
                when (result) {
                    is SyncResult.Success ->
                        if (result.messagesReceived > 0) {
                            "Sync complete: ${result.messagesReceived} new messages"
                        } else {
                            "Sync complete"
                        }
                    is SyncResult.Error -> "Sync failed: ${result.message}"
                    is SyncResult.NoRelay -> "No relay configured"
                    is SyncResult.Timeout -> "Sync timed out"
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.contactToggleResult.collect { result ->
            val message =
                when (result) {
                    ContactToggleResult.Added -> "Saved contact"
                    ContactToggleResult.Removed -> "Removed contact"
                    is ContactToggleResult.Error -> result.message
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            Column {
                SearchableTopAppBar(
                    title = stringResource(R.string.chats_title),
                    // Маяк spends this line on whether anything is getting through.
                    // "Why isn't my message sending" is the first question a new user
                    // has, and the honest answer lived in the interface statistics —
                    // a screen Маяк does not show. A conversation count they can see by
                    // looking at the list underneath.
                    subtitle =
                        if (AudienceProfile.isSimpleUi) {
                            meshStatusText(reachability, nowMs = meshStatusTickMs)
                        } else if (selectedSegment == ChatsSegment.TEXT) {
                            pluralStringResource(
                                R.plurals.conversation_count,
                                chatsState.items.size,
                                chatsState.items.size,
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.call_count,
                                voiceHistoryState.records.size,
                                voiceHistoryState.records.size,
                            )
                        },
                    isSearching = isSearching,
                    searchQuery = if (selectedSegment == ChatsSegment.TEXT) searchQuery else voiceSearchQuery,
                    onSearchQueryChange = {
                        if (selectedSegment == ChatsSegment.TEXT) viewModel.searchQuery.value = it else viewModel.voiceSearchQuery.value = it
                    },
                    onSearchToggle = { isSearching = !isSearching },
                    searchPlaceholder =
                        stringResource(
                            if (selectedSegment == ChatsSegment.TEXT) {
                                R.string.search_conversations_placeholder
                            } else {
                                R.string.search_call_history_placeholder
                            },
                        ),
                    additionalActions = {
                        if (selectedSegment == ChatsSegment.TEXT) {
                            // New group button
                            IconButton(onClick = onNewGroupClick) {
                                Icon(
                                    imageVector = Icons.Default.GroupAdd,
                                    contentDescription = stringResource(R.string.chats_cd_new_group),
                                )
                            }
                            // QR Code button
                            IconButton(onClick = { showQrBottomSheet = true }) {
                                Icon(
                                    imageVector = Icons.Default.QrCode2,
                                    contentDescription = stringResource(R.string.chats_cd_qr_code),
                                )
                            }
                            // Sync button - shows spinner during sync, tapping opens status sheet
                            IconButton(
                                onClick = {
                                    if (isSyncing) {
                                        showSyncStatusSheet = true
                                    } else {
                                        viewModel.syncFromPropagationNode()
                                    }
                                },
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.chats_cd_sync_messages),
                                    )
                                }
                            }
                        } else {
                            IconButton(onClick = { showVoiceActions = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.call_history_more_actions),
                                )
                            }
                            DropdownMenu(
                                expanded = showVoiceActions,
                                onDismissRequest = { showVoiceActions = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.call_history_clear)) },
                                    onClick = {
                                        showVoiceActions = false
                                        showClearCallHistoryDialog = true
                                    },
                                )
                            }
                        }
                    },
                )
                ChatsSegmentSelector(
                    selected = selectedSegment,
                    onSelected = {
                        viewModel.selectedSegment.value = it
                        isSearching = false
                    },
                )
            }
        },
    ) { paddingValues ->
        if (selectedSegment == ChatsSegment.VOICE) {
            VoiceHistoryContent(
                state = voiceHistoryState,
                onRecordClick = viewModel::openCallHistory,
                onRetry = viewModel::retryVoiceHistory,
                listState = voiceListState,
                modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
            )
        } else {
            // Only show loading spinner when loading AND list is empty
            // This prevents flickering when data updates while content is displayed
            when {
                chatsState.isLoading && chatsState.items.isEmpty() -> {
                    LoadingConversationsState(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                    )
                }
                !chatsState.isLoading && chatsState.items.isEmpty() -> {
                    EmptyChatsState(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                                .consumeWindowInsets(paddingValues)
                                .simpleVerticalScrollbar(listState),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(chatsState.items, key = { it.key }) { item ->
                            when (item) {
                                is ChatListItem.Group -> {
                                    val group = item.group
                                    var showGroupMenu by remember { mutableStateOf(false) }
                                    val hapticFeedback = LocalHapticFeedback.current
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        GroupConversationCard(
                                            group = group,
                                            onClick = { onGroupClick(group.groupId) },
                                            onLongPress = {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showGroupMenu = true
                                            },
                                        )
                                        GroupContextMenu(
                                            expanded = showGroupMenu,
                                            onDismiss = { showGroupMenu = false },
                                            onMarkAsRead = {
                                                viewModel.markGroupRead(group.groupId)
                                                showGroupMenu = false
                                            },
                                            onLeaveGroup = {
                                                showGroupMenu = false
                                                groupToLeave = group
                                            },
                                            onDeleteGroup = {
                                                showGroupMenu = false
                                                groupToDelete = group
                                            },
                                        )
                                    }
                                }
                                is ChatListItem.Peer -> {
                                    val conversation = item.conversation
                                    // Per-card state for context menu
                                    val hapticFeedback = LocalHapticFeedback.current
                                    var showMenu by remember { mutableStateOf(false) }
                                    val isSaved by viewModel.isContactSaved(conversation.peerHash).collectAsState()
                                    var contactLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }

                                    // Fetch contact location when menu opens; clear on close
                                    LaunchedEffect(showMenu) {
                                        if (showMenu) {
                                            contactLocation = viewModel.getContactLocation(conversation.peerHash)
                                        } else {
                                            contactLocation = null
                                        }
                                    }

                                    val draftText = draftsMap[conversation.peerHash]

                                    // Wrap card and menu in Box to anchor menu to card
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        ConversationCard(
                                            conversation = conversation,
                                            isSaved = isSaved,
                                            draftText = draftText,
                                            onClick = {
                                                if (pendingSharedText != null) {
                                                    sharedTextViewModel.assignToDestination(conversation.peerHash)
                                                }
                                                if (pendingSharedImages != null) {
                                                    sharedImageViewModel.assignToDestination(conversation.peerHash)
                                                }
                                                onChatClick(conversation.peerHash, conversation.displayName)
                                            },
                                            onLongPress = {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showMenu = true
                                            },
                                            onStarClick = {
                                                if (isSaved) {
                                                    viewModel.removeFromContacts(conversation.peerHash)
                                                } else {
                                                    viewModel.saveToContacts(conversation)
                                                }
                                                showMenu = false
                                            },
                                        )

                                        // Context menu anchored to this card
                                        ConversationContextMenu(
                                            expanded = showMenu,
                                            onDismiss = { showMenu = false },
                                            isSaved = isSaved,
                                            onSaveToContacts = {
                                                viewModel.saveToContacts(conversation)
                                                showMenu = false
                                            },
                                            onRemoveFromContacts = {
                                                viewModel.removeFromContacts(conversation.peerHash)
                                                showMenu = false
                                            },
                                            onMarkAsUnread = {
                                                viewModel.markAsUnread(conversation.peerHash)
                                                showMenu = false
                                                Toast.makeText(context, "Marked as unread", Toast.LENGTH_SHORT).show()
                                            },
                                            onDeleteConversation = {
                                                showMenu = false
                                                selectedConversation = conversation
                                                showDeleteDialog = true
                                            },
                                            onViewDetails = {
                                                showMenu = false
                                                onViewPeerDetails(conversation.peerHash)
                                            },
                                            hasLocation = contactLocation != null,
                                            onLocateOnMap = {
                                                showMenu = false
                                                onLocateOnMap(conversation.peerHash)
                                            },
                                            onBlockUser = {
                                                showMenu = false
                                                selectedConversation = conversation
                                                showBlockDialog = true
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // Bottom spacing for navigation bar (fixed height since M3 NavigationBar consumes the insets)
                        item {
                            Spacer(modifier = Modifier.height(100.dp))
                        }
                    }
                }
            }
        }

        // Leave-group confirmation dialog
        val pendingGroupToLeave = groupToLeave
        if (pendingGroupToLeave != null) {
            AlertDialog(
                onDismissRequest = { groupToLeave = null },
                title = { Text(stringResource(R.string.group_details_leave_title)) },
                text = { Text(stringResource(R.string.group_details_leave_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.leaveGroup(pendingGroupToLeave.groupId)
                            groupToLeave = null
                        },
                    ) { Text(stringResource(R.string.group_details_leave)) }
                },
                dismissButton = {
                    TextButton(onClick = { groupToLeave = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // Delete-group confirmation dialog. Deleting is local-only and does not
        // announce anything, so it stays a separate action from leaving.
        val pendingGroupToDelete = groupToDelete
        if (pendingGroupToDelete != null) {
            AlertDialog(
                onDismissRequest = { groupToDelete = null },
                title = { Text(stringResource(R.string.group_delete_title)) },
                text = { Text(stringResource(R.string.group_delete_body, pendingGroupToDelete.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteGroup(pendingGroupToDelete.groupId)
                            groupToDelete = null
                        },
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { groupToDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        if (showClearCallHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showClearCallHistoryDialog = false },
                title = { Text(stringResource(R.string.call_history_clear_title)) },
                text = { Text(stringResource(R.string.call_history_clear_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearCallHistory()
                            showClearCallHistoryDialog = false
                        },
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCallHistoryDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // Delete confirmation dialog
        val conversationToDelete = selectedConversation
        if (showDeleteDialog && conversationToDelete != null) {
            DeleteConversationDialog(
                peerName = conversationToDelete.displayName,
                onConfirm = {
                    val deletedName = conversationToDelete.displayName
                    viewModel.deleteConversation(conversationToDelete.peerHash)
                    showDeleteDialog = false
                    selectedConversation = null
                    Toast.makeText(context, "Deleted conversation with $deletedName", Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    showDeleteDialog = false
                },
            )
        }

        // Block user confirmation dialog
        val conversationToBlock = selectedConversation
        if (showBlockDialog && conversationToBlock != null) {
            BlockUserDialog(
                peerName = conversationToBlock.displayName,
                isTransportEnabled = isTransportEnabled,
                onConfirm = { deleteMessages, blackholeEnabled ->
                    viewModel.blockUser(
                        peerHash = conversationToBlock.peerHash,
                        peerIdentityHash =
                            conversationToBlock.peerPublicKey?.let {
                                network.zamolxis.app.data.util.HashUtils
                                    .computeIdentityHash(it)
                            },
                        displayName = conversationToBlock.displayName,
                        deleteConversation = deleteMessages,
                        blackholeEnabled = blackholeEnabled,
                    )
                    showBlockDialog = false
                    selectedConversation = null
                    Toast.makeText(context, "Blocked ${conversationToBlock.displayName}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    showBlockDialog = false
                },
            )
        }

        // Sync status bottom sheet - shows real-time propagation sync progress
        if (showSyncStatusSheet) {
            SyncStatusBottomSheet(
                syncProgress = syncProgress,
                onDismiss = { showSyncStatusSheet = false },
                sheetState = syncStatusSheetState,
            )
        }

        // QR Code bottom sheet
        if (showQrBottomSheet) {
            network.zamolxis.app.ui.components.QrCodeBottomSheet(
                onDismiss = { showQrBottomSheet = false },
                onScanQrCode = { onNavigateToQrScanner() },
                onShowQrCode = { showQrCodeDialog = true },
            )
        }

        // QR Code dialog - reuses IdentityQrCodeDialog from settings
        if (showQrCodeDialog && identityHash != null) {
            network.zamolxis.app.ui.screens.settings.dialogs.IdentityQrCodeDialog(
                displayName = settingsState.displayName,
                identityHash = identityHash,
                destinationHash = destinationHash,
                qrCodeData = qrCodeData,
                onDismiss = { showQrCodeDialog = false },
                onShareClick = {
                    val shareText = debugViewModel.generateShareText(settingsState.displayName)
                    if (shareText != null) {
                        val sendIntent =
                            android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                        val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Identity")
                        context.startActivity(shareIntent)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationCard(
    conversation: Conversation,
    isSaved: Boolean = false,
    draftText: String? = null,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onStarClick: () -> Unit = {},
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Box {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(end = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Profile icon with identicon fallback
                Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                    ProfileIcon(
                        iconName = conversation.iconName,
                        foregroundColor = conversation.iconForegroundColor,
                        backgroundColor = conversation.iconBackgroundColor,
                        size = 56.dp,
                        fallbackHash = conversation.peerPublicKey ?: conversation.peerHash.hexStringToByteArray(),
                    )
                    // Unread badge (top-right)
                    if (conversation.unreadCount > 0) {
                        Badge(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text(
                                text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    // Saved contact badge (bottom-right)
                    if (isSaved) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 4.dp, y = 4.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = stringResource(R.string.chats_cd_saved_contact),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier =
                                    Modifier
                                        .size(16.dp)
                                        .align(Alignment.Center),
                            )
                        }
                    }
                }

                // Conversation info
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Display name (nickname > announce name > peer name > hash)
                    Text(
                        text = conversation.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Last message preview (or draft indicator)
                    if (draftText != null) {
                        Text(
                            text =
                                buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.error,
                                            fontStyle = FontStyle.Italic,
                                        ),
                                    ) {
                                        append("Draft: ")
                                    }
                                    withStyle(
                                        SpanStyle(
                                            fontStyle = FontStyle.Italic,
                                        ),
                                    ) {
                                        append(draftText)
                                    }
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            text = conversation.lastMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (conversation.unreadCount > 0) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            fontWeight = if (conversation.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Timestamp
                Text(
                    text = formatTimestamp(conversation.lastMessageTimestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Top),
                )
            }

            // Star button overlay
            StarToggleButton(
                isStarred = isSaved,
                onClick = onStarClick,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
            )
        }
    }
}

@Composable
fun ConversationContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isSaved: Boolean,
    onSaveToContacts: () -> Unit,
    onRemoveFromContacts: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onDeleteConversation: () -> Unit,
    onViewDetails: () -> Unit,
    hasLocation: Boolean = false,
    onLocateOnMap: () -> Unit = {},
    onBlockUser: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        offset = DpOffset(x = 8.dp, y = 0.dp),
    ) {
        // Save/Remove from contacts
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(if (isSaved) "Remove from Contacts" else "Save to Contacts")
            },
            onClick = {
                if (isSaved) {
                    onRemoveFromContacts()
                } else {
                    onSaveToContacts()
                }
            },
        )

        HorizontalDivider()

        // Mark as unread
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.MarkEmailUnread,
                    contentDescription = null,
                )
            },
            text = {
                Text(stringResource(R.string.chats_mark_unread))
            },
            onClick = onMarkAsUnread,
        )

        // View details
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                )
            },
            text = {
                Text(stringResource(R.string.chats_view_peer_details))
            },
            onClick = onViewDetails,
        )

        // Locate on map (only shown if contact has a known location)
        if (hasLocation) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                text = {
                    Text(stringResource(R.string.locate_on_map))
                },
                onClick = onLocateOnMap,
            )
        }

        HorizontalDivider()

        // Delete conversation
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.chats_delete_conversation),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDeleteConversation,
        )

        // Block user
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.chats_block_user),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onBlockUser,
        )
    }
}

@Composable
fun DeleteConversationDialog(
    peerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(stringResource(R.string.chats_delete_conversation_title))
        },
        text = {
            Text(stringResource(R.string.chats_delete_conversation_body, peerName))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun BlockUserDialog(
    peerName: String,
    isTransportEnabled: Boolean = false,
    onConfirm: (deleteMessages: Boolean, blackholeEnabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleteMessages by remember { mutableStateOf(false) }
    var blackholeEnabled by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(stringResource(R.string.chats_block_title, peerName))
        },
        text = {
            Column {
                Text(stringResource(R.string.chats_block_body))
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = deleteMessages,
                        onCheckedChange = { deleteMessages = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.chats_block_also_delete),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = blackholeEnabled,
                        onCheckedChange = { blackholeEnabled = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.chats_block_also_blackhole),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (blackholeEnabled && !isTransportEnabled) {
                    Text(
                        text = stringResource(R.string.chats_block_transport_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 48.dp, top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(deleteMessages, blackholeEnabled) },
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text(stringResource(R.string.common_block))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun LoadingConversationsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.chats_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyChatsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Chat,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.chats_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.chats_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// Helper function to convert hex string to byte array (for identicon)
private fun String.hexStringToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

// Reuse timestamp formatting from MessagingScreen
@Composable
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> stringResource(R.string.time_now)
        diff < 3600_000 -> {
            val minutes = (diff / 60_000).toInt()
            "${minutes}m"
        }
        diff < 86400_000 -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        diff < 604800_000 -> { // Less than a week
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

/**
 * Chat-list row for a group conversation: group icon, name, last-message
 * preview and unread badge. Mirrors [ConversationCard]'s shape so both item
 * kinds sit in the merged list without visual seams.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupConversationCard(
    group: GroupEntity,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Groups,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                group.lastMessage?.let { preview ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (group.unreadCount > 0) {
                Badge { Text(group.unreadCount.toString()) }
            }
        }
    }
}

/** Long-press menu for a group row in the chat list. */
@Composable
private fun GroupContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onMarkAsRead: () -> Unit,
    onLeaveGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        offset = DpOffset(x = 8.dp, y = 0.dp),
    ) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.MarkEmailUnread,
                    contentDescription = null,
                )
            },
            text = { Text(stringResource(R.string.group_mark_read)) },
            onClick = onMarkAsRead,
        )
        HorizontalDivider()
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = { Text(stringResource(R.string.group_details_leave)) },
            onClick = onLeaveGroup,
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = { Text(stringResource(R.string.group_delete)) },
            onClick = onDeleteGroup,
        )
    }
}
