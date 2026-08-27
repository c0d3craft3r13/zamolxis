package network.zamolxis.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import network.zamolxis.app.R
import network.zamolxis.app.data.model.GroupRole
import network.zamolxis.app.ui.components.GroupMemberPicker
import network.zamolxis.app.ui.components.ProfileIcon
import network.zamolxis.app.util.HexUtils.hexStringToByteArray
import network.zamolxis.app.viewmodel.GroupChatViewModel
import network.zamolxis.app.viewmodel.GroupMemberUi

/**
 * Group details: name (renameable by admins), the member list, and the admin
 * membership actions. "Leave group" is available to everyone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailsScreen(
    groupId: String,
    onBackClick: () -> Unit = {},
    onLeftGroup: () -> Unit = {},
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val contacts by viewModel.sendableContacts.collectAsStateWithLifecycle()

    val isAdmin = members.firstOrNull { it.isMe }?.role == GroupRole.ADMIN

    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddMembersSheet by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<GroupMemberUi?>(null) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    val actionFailedText = stringResource(R.string.group_action_failed)

    LaunchedEffect(groupId) {
        viewModel.openGroup(groupId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_details_title)) },
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
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Group name row
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = group?.name ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isAdmin) {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.group_details_rename),
                            )
                        }
                    }
                }
            }

            // Members header + add action
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.group_details_members),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (isAdmin) {
                        TextButton(onClick = { showAddMembersSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.group_details_add_members))
                        }
                    }
                }
            }

            items(members, key = { it.memberHash }) { member ->
                GroupMemberRow(
                    member = member,
                    canRemove = isAdmin && !member.isMe && !member.isLeft,
                    onRemove = { memberToRemove = member },
                )
            }

            // Leave group
            item {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showLeaveDialog = true },
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.group_details_leave))
                }
            }
        }
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(group?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.group_details_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.group_new_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        showRenameDialog = false
                        scope.launch {
                            viewModel
                                .renameGroup(groupId, newName)
                                .onFailure {
                                    Toast.makeText(context, actionFailedText, Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showAddMembersSheet) {
        val sheetState = rememberModalBottomSheetState()
        var selected by remember { mutableStateOf(setOf<String>()) }
        val memberHashes = members.map { it.memberHash.lowercase() }.toSet()
        val candidates = contacts.filter { it.destinationHash.lowercase() !in memberHashes }
        ModalBottomSheet(
            onDismissRequest = { showAddMembersSheet = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.group_details_add_members),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                GroupMemberPicker(
                    contacts = candidates,
                    selectedHashes = selected,
                    onToggle = { hash ->
                        selected = if (hash in selected) selected - hash else selected + hash
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        showAddMembersSheet = false
                        scope.launch {
                            viewModel
                                .addMembers(groupId, selected.toList())
                                .onFailure {
                                    Toast.makeText(context, actionFailedText, Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.group_details_add))
                }
            }
        }
    }

    val removeTarget = memberToRemove
    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            title = { Text(stringResource(R.string.group_details_remove_title)) },
            text = { Text(stringResource(R.string.group_details_remove_body, removeTarget.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        memberToRemove = null
                        scope.launch {
                            viewModel
                                .removeMember(groupId, removeTarget.memberHash)
                                .onFailure {
                                    Toast.makeText(context, actionFailedText, Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) { Text(stringResource(R.string.group_details_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.group_details_leave_title)) },
            text = { Text(stringResource(R.string.group_details_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveDialog = false
                        scope.launch {
                            viewModel
                                .leaveGroup(groupId)
                                .onSuccess { onLeftGroup() }
                                .onFailure {
                                    Toast.makeText(context, actionFailedText, Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) { Text(stringResource(R.string.group_details_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GroupMemberRow(
    member: GroupMemberUi,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ProfileIcon(
            iconName = null,
            foregroundColor = null,
            backgroundColor = null,
            size = 40.dp,
            fallbackHash = member.memberHash.hexStringToByteArray(),
            modifier = Modifier.clip(CircleShape),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (member.isMe) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.group_member_you),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (member.role == GroupRole.ADMIN) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.group_role_admin),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (member.isLeft) {
                Text(
                    text = stringResource(R.string.group_member_left),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.PersonRemove,
                    contentDescription = stringResource(R.string.group_details_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
