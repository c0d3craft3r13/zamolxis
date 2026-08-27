package network.zamolxis.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import network.zamolxis.app.data.db.dao.GroupMessageDao
import network.zamolxis.app.data.db.entity.ContactStatus
import network.zamolxis.app.data.db.entity.GroupEntity
import network.zamolxis.app.data.model.EnrichedContact
import network.zamolxis.app.data.model.GroupRole
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.data.repository.GroupRepository
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.notifications.NotificationHelper
import network.zamolxis.app.service.ActiveConversationManager
import network.zamolxis.app.service.group.GroupChatManager
import javax.inject.Inject

/**
 * UI model for one group message bubble.
 *
 * [aggregateStatus] is the per-message fan-out rollup reduced to the lowercase
 * status token the message-status glyph logic already understands
 * ("pending"/"sent"/"delivered"/"failed").
 */
data class GroupMessageUi(
    val msgId: String,
    val content: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val senderHash: String,
    val senderName: String?,
    val aggregateStatus: String?,
)

/** UI model for one row of the group member list. */
data class GroupMemberUi(
    val memberHash: String,
    val displayName: String,
    val role: GroupRole,
    val isLeft: Boolean,
    val isMe: Boolean,
)

/**
 * ViewModel for the group-chat screens (message list, creation, details).
 *
 * One shared instance pattern, mirroring the messaging flow: screens call
 * [openGroup] with the nav argument and every state flow follows the open
 * group. Group management actions are thin suspend wrappers over
 * [GroupChatManager], which owns the fan-out transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupChatViewModel
    @Inject
    constructor(
        private val groupRepository: GroupRepository,
        private val groupChatManager: GroupChatManager,
        private val contactRepository: ContactRepository,
        private val identityRepository: IdentityRepository,
        private val groupMessageDao: GroupMessageDao,
        private val activeConversationManager: ActiveConversationManager,
        private val notificationHelper: NotificationHelper,
    ) : ViewModel() {
        private val openGroupId = MutableStateFlow<String?>(null)

        /** destinationHash (lowercase) -> display name, for sender/member labels. */
        private val contactNames: StateFlow<Map<String, String>> =
            contactRepository
                .getEnrichedContacts()
                .map { contacts ->
                    contacts.associate { it.destinationHash.lowercase() to it.displayName }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyMap(),
                )

        private val myHash: StateFlow<String?> =
            identityRepository.activeIdentity
                .map { it?.destinationHash?.lowercase() }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = null,
                )

        /** The currently open group, null until [openGroup] runs. */
        val group: StateFlow<GroupEntity?> =
            openGroupId
                .flatMapLatest { groupId ->
                    if (groupId == null) flowOf(null) else groupRepository.observeGroup(groupId)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = null,
                )

        val members: StateFlow<List<GroupMemberUi>> =
            combine(
                openGroupId.flatMapLatest { groupId ->
                    if (groupId == null) flowOf(emptyList()) else groupRepository.observeMembers(groupId)
                },
                contactNames,
                myHash,
            ) { memberEntities, names, me ->
                memberEntities.map { entity ->
                    GroupMemberUi(
                        memberHash = entity.memberHash,
                        displayName = names[entity.memberHash.lowercase()] ?: entity.memberHash.take(16),
                        role = GroupRole.fromStored(entity.role),
                        isLeft = entity.leftAt != null,
                        isMe = entity.memberHash.lowercase() == me,
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList(),
            )

        /**
         * Messages of the open group, newest first (the list is rendered with
         * `reverseLayout`), joined with the fan-out aggregate status and the
         * sender display names.
         */
        val messages: StateFlow<List<GroupMessageUi>> =
            combine(
                openGroupId.flatMapLatest { groupId ->
                    if (groupId == null) flowOf(emptyList()) else groupMessageDao.observeMessages(groupId)
                },
                openGroupId.flatMapLatest { groupId ->
                    if (groupId == null) flowOf(emptyList()) else groupRepository.observeAggregateStatuses(groupId)
                },
                contactNames,
            ) { messageEntities, aggregates, names ->
                val statusByMsgId = aggregates.associate { it.msgId to it.status }
                messageEntities
                    .sortedByDescending { it.receivedAt }
                    .map { entity ->
                        GroupMessageUi(
                            msgId = entity.msgId,
                            content = entity.content,
                            timestamp = entity.timestamp,
                            isFromMe = entity.isFromMe,
                            senderHash = entity.senderHash,
                            senderName = if (entity.isFromMe) null else names[entity.senderHash.lowercase()],
                            aggregateStatus = statusByMsgId[entity.msgId]?.let(::toUiStatus),
                        )
                    }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList(),
            )

        /** Contacts that can actually receive a message, for the member pickers. */
        val sendableContacts: StateFlow<List<EnrichedContact>> =
            contactRepository
                .getEnrichedContacts()
                .map { contacts ->
                    contacts.filter { it.status == ContactStatus.ACTIVE && it.publicKey != null }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyList(),
                )

        /**
         * Transient send failures, surfaced as a toast by the screen.
         *
         * Carries no payload on purpose: the underlying messages come from
         * `require`/`error` inside [GroupChatManager] and are developer English,
         * so the screen shows a localized string and the detail stays in the log.
         */
        private val sendErrorsChannel = Channel<Unit>(Channel.BUFFERED)
        val sendErrors: Flow<Unit> = sendErrorsChannel.receiveAsFlow()

        /** Switch every state flow to [groupId] and clear its unread counter. */
        fun openGroup(groupId: String) {
            if (openGroupId.value == groupId) return
            openGroupId.value = groupId
            // Suppress + dismiss this group's notifications while it is open,
            // mirroring the 1:1 conversation behavior. Group notifications carry
            // the synthetic "group:<id>" key (see GroupChatManager).
            val notificationKey = GroupChatManager.GROUP_NOTIFICATION_KEY_PREFIX + groupId
            activeConversationManager.setActive(notificationKey)
            notificationHelper.cancelNotificationForConversation(notificationKey)
            viewModelScope.launch {
                runCatching { groupRepository.markGroupRead(groupId) }
            }
        }

        override fun onCleared() {
            activeConversationManager.setActive(null)
            super.onCleared()
        }

        fun send(text: String) {
            val groupId = openGroupId.value ?: return
            if (text.isBlank()) return
            viewModelScope.launch {
                groupChatManager
                    .sendGroupMessage(groupId, text.trim())
                    .onFailure {
                        Log.w(TAG, "Group send failed for $groupId", it)
                        sendErrorsChannel.send(Unit)
                    }
            }
        }

        suspend fun createGroup(
            name: String,
            memberHashes: List<String>,
        ): Result<String> = groupChatManager.createGroup(name, memberHashes)

        suspend fun renameGroup(
            groupId: String,
            newName: String,
        ): Result<Unit> = groupChatManager.renameGroup(groupId, newName)

        suspend fun addMembers(
            groupId: String,
            memberHashes: List<String>,
        ): Result<Unit> = groupChatManager.addMembers(groupId, memberHashes)

        suspend fun removeMember(
            groupId: String,
            memberHash: String,
        ): Result<Unit> = groupChatManager.removeMember(groupId, memberHash)

        suspend fun leaveGroup(groupId: String): Result<Unit> = groupChatManager.leaveGroup(groupId)

        /**
         * Drop a group from this device: its history, roster and fan-out status
         * rows. Local only — the other members keep theirs. Leaving first is
         * what tells them we are gone; this is the cleanup afterwards.
         */
        suspend fun deleteGroup(groupId: String): Result<Unit> = runCatching { groupRepository.deleteGroup(groupId) }

        companion object {
            private const val TAG = "GroupChatViewModel"

            /** Fold a stored GroupMessageStatus name into the glyph status tokens. */
            private fun toUiStatus(status: String): String =
                when (status) {
                    "DELIVERED", "READ" -> "delivered"
                    "SENT" -> "sent"
                    "FAILED" -> "failed"
                    else -> "pending"
                }
        }
    }
