package network.zamolxis.app.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import network.zamolxis.app.data.repository.AnnounceRepository
import network.zamolxis.app.data.repository.BlockedPeerRepository
import network.zamolxis.app.data.repository.CallHistoryRepository
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.data.repository.Conversation
import network.zamolxis.app.data.repository.ConversationRepository
import network.zamolxis.app.data.repository.GroupRepository
import network.zamolxis.app.data.repository.ReceivedLocationRepository
import network.zamolxis.app.data.model.CallHistoryRecord
import network.zamolxis.app.data.db.entity.GroupEntity
import network.zamolxis.app.rns.api.RnsCore
import network.zamolxis.app.rns.api.RnsTelephony
import network.zamolxis.app.rns.api.model.CallState
import network.zamolxis.app.rns.api.model.VoiceCallState
import network.zamolxis.app.service.IdentityResolutionManager
import network.zamolxis.app.service.PropagationNodeManager
import network.zamolxis.app.service.SyncProgress
import network.zamolxis.app.service.SyncResult
import network.zamolxis.app.service.group.GroupChatManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * UI state for the Chats tab, including loading status.
 */
data class ChatsState(
    val conversations: List<Conversation> = emptyList(),
    val groups: List<GroupEntity> = emptyList(),
    val items: List<ChatListItem> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * One row of the merged chat list: either a 1:1 conversation or a group.
 * [key] is unique across both kinds, for the LazyColumn item keys.
 */
sealed interface ChatListItem {
    val key: String
    val sortTimestamp: Long

    data class Peer(
        val conversation: Conversation,
    ) : ChatListItem {
        override val key: String get() = "peer:${conversation.peerHash}"
        override val sortTimestamp: Long get() = conversation.lastMessageTimestamp
    }

    data class Group(
        val group: GroupEntity,
    ) : ChatListItem {
        override val key: String get() = "group:${group.groupId}"
        override val sortTimestamp: Long get() = group.lastMessageTimestamp ?: group.createdAt
    }
}

enum class ChatsSegment {
    TEXT,
    VOICE,
}

data class VoiceHistoryState(
    val records: List<CallHistoryRecord> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val activeCallAttemptId: String? = null,
)

private data class VoiceHistoryScope(
    val localIdentityHash: String?,
    val query: String,
)

private data class LiveCallOwnership(
    val callAttemptId: String,
    val remoteIdentityHash: String,
)

private fun CallState.isLiveCallState(): Boolean =
    when (this) {
        is CallState.Connecting, is CallState.Ringing, is CallState.Incoming, is CallState.Active -> true
        else -> false
    }

sealed interface CallHistoryNavigation {
    data class Details(
        val callAttemptId: String,
    ) : CallHistoryNavigation

    data class ActiveCall(
        val callAttemptId: String,
        val localIdentityHash: String,
        val remoteIdentityHash: String,
        val profileCode: Int,
    ) : CallHistoryNavigation
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModel
    @Inject
    @Suppress("LongParameterList")
    constructor(
        private val conversationRepository: ConversationRepository,
        private val callHistoryRepository: CallHistoryRepository,
        private val contactRepository: ContactRepository,
        private val groupRepository: GroupRepository,
        private val groupChatManager: GroupChatManager,
        private val announceRepository: AnnounceRepository,
        private val blockedPeerRepository: BlockedPeerRepository,
        private val rnsCore: RnsCore,
        private val rnsTelephony: RnsTelephony,
        private val propagationNodeManager: PropagationNodeManager,
        private val receivedLocationRepository: ReceivedLocationRepository,
        private val identityResolutionManager: IdentityResolutionManager,
        private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) : ViewModel() {
        companion object {
            private const val TAG = "ChatsViewModel"
            private const val LIVE_OWNERSHIP_RETRY_MILLIS = 500L
            private const val VOICE_QUERY_KEY = "voice_history_query"
            private const val SELECTED_SEGMENT_KEY = "chats_selected_segment"
        }

        private val _callHistoryNavigation = MutableSharedFlow<CallHistoryNavigation>()
        val callHistoryNavigation = _callHistoryNavigation.asSharedFlow()

        fun openCallHistory(record: CallHistoryRecord) {
            viewModelScope.launch {
                val liveCall = rnsTelephony.getCallState().getOrNull()
                val destination =
                    if (record.matchesLiveCall(liveCall)) {
                        CallHistoryNavigation.ActiveCall(
                            callAttemptId = record.callAttemptId,
                            localIdentityHash = record.localIdentityHash,
                            remoteIdentityHash = record.remoteIdentityHash,
                            profileCode = record.codecProfileCode ?: -1,
                        )
                    } else {
                        CallHistoryNavigation.Details(record.callAttemptId)
                    }
                _callHistoryNavigation.emit(destination)
            }
        }

        private fun CallHistoryRecord.matchesLiveCall(liveCall: VoiceCallState?): Boolean =
            endedAt == null &&
                liveCall?.callAttemptId == callAttemptId &&
                liveCall.status in setOf("connecting", "ringing", "incoming", "active") &&
                liveCall.remoteIdentity.equals(remoteIdentityHash, ignoreCase = true)

        // Whether Reticulum transport mode is enabled (for blackhole option)
        private val _isTransportEnabled = MutableStateFlow(false)
        val isTransportEnabled: StateFlow<Boolean> = _isTransportEnabled

        init {
            viewModelScope.launch {
                try {
                    _isTransportEnabled.value = rnsCore.isTransportEnabled()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to check transport status", e)
                }
            }
        }

        // Sync state from PropagationNodeManager
        val isSyncing: StateFlow<Boolean> = propagationNodeManager.isSyncing

        // Manual sync result events for Snackbar notifications
        val manualSyncResult: SharedFlow<SyncResult> = propagationNodeManager.manualSyncResult

        // Sync progress for UI display
        val syncProgress: StateFlow<SyncProgress> = propagationNodeManager.syncProgress

        // Cache for contact saved state flows to prevent flickering on recomposition
        private val contactSavedCache = ConcurrentHashMap<String, StateFlow<Boolean>>()

        private val _contactToggleResult = MutableSharedFlow<ContactToggleResult>()
        val contactToggleResult: SharedFlow<ContactToggleResult> = _contactToggleResult.asSharedFlow()

        // Draft texts keyed by peerHash - for showing "Draft:" in conversation list
        val draftsMap: StateFlow<Map<String, String>> =
            conversationRepository
                .observeDrafts()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyMap(),
                )

        // Search query state
        val searchQuery = MutableStateFlow("")
        val voiceSearchQuery = MutableStateFlow(savedStateHandle[VOICE_QUERY_KEY] ?: "")
        private val voiceHistoryRefresh = MutableStateFlow(0)
        private var cachedVoiceHistory = emptyList<CallHistoryRecord>()
        private var cachedVoiceHistoryIdentity: String? = null
        private var cachedVoiceHistoryQuery = ""
        private var cachedActiveCallAttemptId: String? = null
        val selectedSegment =
            MutableStateFlow(
                savedStateHandle
                    .get<String>(SELECTED_SEGMENT_KEY)
                    ?.let { runCatching { ChatsSegment.valueOf(it) }.getOrNull() }
                    ?: ChatsSegment.TEXT,
            )

        init {
            viewModelScope.launch {
                voiceSearchQuery.collect { savedStateHandle[VOICE_QUERY_KEY] = it }
            }
            viewModelScope.launch {
                selectedSegment.collect { savedStateHandle[SELECTED_SEGMENT_KEY] = it.name }
            }
        }

        // Filtered conversations based on search query, merged with group chats
        // and sorted by activity, with loading state.
        // onStart emits loading state each time flow is collected (tab switch, screen entry)
        val chatsState: StateFlow<ChatsState> =
            combine(
                searchQuery.flatMapLatest { query ->
                    if (query.isBlank()) {
                        conversationRepository.getConversations()
                    } else {
                        conversationRepository.searchConversations(query)
                    }
                },
                groupRepository.observeGroupOverviews(),
                searchQuery,
            ) { conversations, groups, query ->
                // Deduplicate by peerHash to prevent LazyColumn duplicate key crash
                // (issue #542: transient duplicates from Room LEFT JOIN race conditions)
                val dedupedConversations = conversations.distinctBy { it.peerHash }
                val filteredGroups =
                    if (query.isBlank()) {
                        groups
                    } else {
                        groups.filter { it.name.contains(query, ignoreCase = true) }
                    }
                val items =
                    (
                        dedupedConversations.map { ChatListItem.Peer(it) } +
                            filteredGroups.map { ChatListItem.Group(it) }
                    ).sortedByDescending { it.sortTimestamp }
                ChatsState(
                    conversations = dedupedConversations,
                    groups = filteredGroups,
                    items = items,
                    isLoading = false,
                )
            }.onStart {
                emit(ChatsState(isLoading = true))
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = ChatsState(isLoading = true),
            )

        val voiceHistoryState: StateFlow<VoiceHistoryState> =
            combine(
                callHistoryRepository.observeActiveIdentityHash(),
                voiceSearchQuery,
                voiceHistoryRefresh,
            ) { identityHash, query, _ -> VoiceHistoryScope(identityHash, query) }
                .flatMapLatest { scope ->
                    val preserveCache =
                        cachedVoiceHistoryIdentity == scope.localIdentityHash &&
                            cachedVoiceHistoryQuery == scope.query
                    val loadingRecords = if (preserveCache) cachedVoiceHistory else emptyList()
                    val history =
                        scope.localIdentityHash?.let {
                            callHistoryRepository.observeHistory(it, scope.query)
                        } ?: flowOf(emptyList())
                    val liveCall =
                        rnsTelephony.callState
                            .flatMapLatest { coarseState ->
                                flow {
                                    if (!coarseState.isLiveCallState()) {
                                        emit(null)
                                        return@flow
                                    }
                                    while (true) {
                                        val ownership = currentLiveCallOwnership()
                                        emit(ownership.getOrNull())
                                        if (ownership.isSuccess) break
                                        delay(LIVE_OWNERSHIP_RETRY_MILLIS)
                                    }
                                }
                            }.catch { emit(null) }
                    combine(history, liveCall) { records, liveOwnership ->
                        val activeAttemptId =
                            liveOwnership
                                ?.takeIf { ownership ->
                                    records.any { record ->
                                        record.callAttemptId == ownership.callAttemptId &&
                                            record.remoteIdentityHash.equals(
                                                ownership.remoteIdentityHash,
                                                ignoreCase = true,
                                            )
                                    }
                                }?.callAttemptId
                        cachedVoiceHistory = records
                        cachedVoiceHistoryIdentity = scope.localIdentityHash
                        cachedVoiceHistoryQuery = scope.query
                        cachedActiveCallAttemptId = activeAttemptId
                        VoiceHistoryState(
                            records = records,
                            isLoading = false,
                            activeCallAttemptId = activeAttemptId,
                        )
                    }.catch {
                        emit(
                            VoiceHistoryState(
                                records = loadingRecords,
                                isLoading = false,
                                hasError = true,
                                activeCallAttemptId = cachedActiveCallAttemptId,
                            ),
                        )
                    }.onStart {
                        emit(
                            VoiceHistoryState(
                                records = loadingRecords,
                                isLoading = true,
                                activeCallAttemptId = cachedActiveCallAttemptId.takeIf { preserveCache },
                            ),
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = VoiceHistoryState(isLoading = true),
                )

        private suspend fun currentLiveCallOwnership(): Result<LiveCallOwnership?> =
            runCatching {
                val call = rnsTelephony.getCallState().getOrThrow()
                if (call.status !in setOf("connecting", "ringing", "incoming", "active")) return@runCatching null
                val attemptId = call.callAttemptId?.takeIf(String::isNotBlank) ?: return@runCatching null
                val remoteIdentity = call.remoteIdentity?.takeIf(String::isNotBlank) ?: return@runCatching null
                LiveCallOwnership(attemptId, remoteIdentity)
            }

        fun retryVoiceHistory() {
            voiceHistoryRefresh.value++
        }

        fun deleteCallHistory(callAttemptId: String) {
            viewModelScope.launch {
                callHistoryRepository.deleteFinalized(callAttemptId)
            }
        }

        fun clearCallHistory() {
            viewModelScope.launch {
                callHistoryRepository.clearFinalized()
            }
        }

        fun deleteConversation(peerHash: String) {
            viewModelScope.launch {
                try {
                    conversationRepository.deleteConversation(peerHash)
                    Log.d(TAG, "Deleted conversation with $peerHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting conversation", e)
                }
            }
        }

        fun markAsUnread(peerHash: String) {
            viewModelScope.launch {
                try {
                    conversationRepository.markConversationAsUnread(peerHash)
                    Log.d(TAG, "Marked conversation $peerHash as unread")
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking conversation as unread", e)
                }
            }
        }

        fun markGroupRead(groupId: String) {
            viewModelScope.launch {
                try {
                    groupRepository.markGroupRead(groupId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking group $groupId as read", e)
                }
            }
        }

        /** Leave a group from the chat list context menu. */
        fun leaveGroup(groupId: String) {
            viewModelScope.launch {
                groupChatManager
                    .leaveGroup(groupId)
                    .onFailure { Log.e(TAG, "Error leaving group $groupId", it) }
            }
        }

        /**
         * Remove a group from this device — history, roster and fan-out status
         * rows. Local only; the other members are not told, so a group we are
         * still in will simply reappear on the next message. Leaving first is
         * what stops that.
         */
        fun deleteGroup(groupId: String) {
            viewModelScope.launch {
                try {
                    groupRepository.deleteGroup(groupId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting group $groupId", e)
                }
            }
        }

        /**
         * Save a conversation peer to contacts
         */
        fun saveToContacts(conversation: Conversation) {
            viewModelScope.launch {
                try {
                    val publicKey = resolvePeerPublicKey(conversation)

                    if (publicKey == null) {
                        Log.e(TAG, "Cannot save to contacts: Public key not available for ${conversation.peerHash}")
                        _contactToggleResult.emit(
                            ContactToggleResult.Error("Identity not available - peer hasn't announced"),
                        )
                        return@launch
                    }

                    val result =
                        contactRepository.addContactFromConversation(
                            destinationHash = conversation.peerHash,
                            publicKey = publicKey,
                        )

                    result.fold(
                        onSuccess = {
                            Log.d(TAG, "Saved ${conversation.peerHash.take(16)} to contacts")
                            viewModelScope.launch(Dispatchers.IO) {
                                identityResolutionManager.requestPathForContact(conversation.peerHash)
                            }
                            _contactToggleResult.emit(ContactToggleResult.Added)
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to save ${conversation.peerHash.take(16)} to contacts", error)
                            _contactToggleResult.emit(
                                ContactToggleResult.Error(error.message ?: "Failed to save contact"),
                            )
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving to contacts", e)
                    _contactToggleResult.emit(
                        ContactToggleResult.Error(e.message ?: "Failed to save contact"),
                    )
                }
            }
        }

        /**
         * Remove a conversation peer from contacts
         */
        fun removeFromContacts(peerHash: String) {
            viewModelScope.launch {
                try {
                    contactRepository.deleteContact(peerHash)
                    Log.d(TAG, "Removed $peerHash from contacts")
                    _contactToggleResult.emit(ContactToggleResult.Removed)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing from contacts", e)
                    _contactToggleResult.emit(
                        ContactToggleResult.Error(e.message ?: "Failed to remove contact"),
                    )
                }
            }
        }

        /**
         * Block a user: persist to DB, notify LXMF router, optionally blackhole and delete conversation.
         */
        fun blockUser(
            peerHash: String,
            peerIdentityHash: String?,
            displayName: String?,
            deleteConversation: Boolean,
            blackholeEnabled: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    blockedPeerRepository.blockPeer(peerHash, peerIdentityHash, displayName, blackholeEnabled)
                    rnsCore.blockDestination(peerHash)
                    if (blackholeEnabled && peerIdentityHash != null) {
                        rnsCore.blackholeIdentity(peerIdentityHash)
                    }
                    if (deleteConversation) {
                        conversationRepository.deleteConversation(peerHash)
                    }
                    Log.d(TAG, "Blocked user ${peerHash.take(16)} (blackhole=$blackholeEnabled, delete=$deleteConversation)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error blocking user", e)
                }
            }
        }

        private suspend fun resolvePeerPublicKey(conversation: Conversation): ByteArray? =
            conversation.peerPublicKey
                ?: conversationRepository.getConversation(conversation.peerHash)?.peerPublicKey
                ?: conversationRepository.getPeerPublicKey(conversation.peerHash)
                ?: announceRepository.getAnnounce(conversation.peerHash)?.publicKey

        /**
         * Check if a peer is saved as a contact.
         * Uses a cache to prevent flickering when the LazyColumn recomposes.
         */
        fun isContactSaved(peerHash: String): StateFlow<Boolean> =
            contactSavedCache.getOrPut(peerHash) {
                contactRepository
                    .hasContactFlow(peerHash)
                    .stateIn(
                        scope = viewModelScope,
                        started = SharingStarted.WhileSubscribed(5000),
                        initialValue = false,
                    )
            }

        /**
         * Trigger a manual sync with the propagation node.
         */
        fun syncFromPropagationNode() {
            viewModelScope.launch {
                try {
                    propagationNodeManager.triggerSync()
                    Log.d(TAG, "Manual sync triggered from ChatsScreen")
                } catch (e: Exception) {
                    Log.e(TAG, "Error triggering manual sync", e)
                }
            }
        }

        /**
         * Get the latest known, non-expired location for a peer.
         * Returns a Pair(latitude, longitude) or null if no valid location is known.
         */
        suspend fun getContactLocation(peerHash: String): Pair<Double, Double>? = receivedLocationRepository.getContactLocation(peerHash)
    }
