package network.zamolxis.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.zamolxis.app.R
import network.zamolxis.app.ui.components.MessageStatusIndicator
import network.zamolxis.app.viewmodel.GroupChatViewModel
import network.zamolxis.app.viewmodel.GroupMessageUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Group conversation screen: message history with per-sender labels on
 * incoming bubbles and the fan-out aggregate status glyph on outgoing ones,
 * plus a text-only composer. Attachments stay exclusive to the 1:1 composer
 * for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: String,
    onBackClick: () -> Unit = {},
    onGroupDetailsClick: (groupId: String) -> Unit = {},
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val group by viewModel.group.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var composerText by remember { mutableStateOf("") }
    val sendFailedText = stringResource(R.string.group_send_failed)

    LaunchedEffect(groupId) {
        viewModel.openGroup(groupId)
    }

    LaunchedEffect(Unit) {
        viewModel.sendErrors.collect {
            Toast.makeText(context, sendFailedText, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable { onGroupDetailsClick(groupId) },
                    ) {
                        Text(
                            text = group?.name ?: stringResource(R.string.group_chat_title_fallback),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                pluralStringResource(
                                    R.plurals.group_member_count,
                                    members.size,
                                    members.size,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.msgId }) { message ->
                    GroupMessageBubble(message)
                }
            }

            // Text-only composer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                OutlinedTextField(
                    value = composerText,
                    onValueChange = { composerText = it },
                    placeholder = { Text(stringResource(R.string.messaging_input_placeholder)) },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = composerText.isNotBlank(),
                    onClick = {
                        viewModel.send(composerText)
                        composerText = ""
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.messaging_cd_send),
                        tint =
                            if (composerText.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

/**
 * Simple text-only bubble in the visual style of the 1:1 [MessageBubble] —
 * that one is too coupled to the 1:1 messaging state (attachments, voice,
 * replies) to drive cleanly from group data.
 */
@Composable
private fun GroupMessageBubble(message: GroupMessageUi) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start,
    ) {
        if (!message.isFromMe) {
            Text(
                text = message.senderName ?: message.senderHash.take(16),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape =
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.isFromMe) 16.dp else 4.dp,
                    bottomEnd = if (message.isFromMe) 4.dp else 16.dp,
                ),
            color =
                if (message.isFromMe) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (message.isFromMe) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = formatGroupMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (message.isFromMe) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            },
                    )
                    val status = message.aggregateStatus
                    if (message.isFromMe && status != null) {
                        MessageStatusIndicator(
                            status = status,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

private fun formatGroupMessageTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
