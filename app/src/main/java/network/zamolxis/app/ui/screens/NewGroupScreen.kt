package network.zamolxis.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import network.zamolxis.app.R
import network.zamolxis.app.ui.components.GroupMemberPicker
import network.zamolxis.app.viewmodel.GroupChatViewModel

/**
 * Create a new group: name field plus a multi-select member picker. On success
 * the caller navigates straight into the new group's chat, popping this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(
    onBackClick: () -> Unit = {},
    onGroupCreated: (groupId: String) -> Unit = {},
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by viewModel.sendableContacts.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var isCreating by remember { mutableStateOf(false) }
    val createFailedText = stringResource(R.string.group_create_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_new_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = name.isNotBlank() && selected.isNotEmpty() && !isCreating,
                        onClick = {
                            isCreating = true
                            scope.launch {
                                viewModel
                                    .createGroup(name, selected.toList())
                                    .onSuccess { groupId -> onGroupCreated(groupId) }
                                    .onFailure {
                                        isCreating = false
                                        Toast.makeText(context, createFailedText, Toast.LENGTH_SHORT).show()
                                    }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.group_new_create))
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
                    .consumeWindowInsets(paddingValues),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.group_new_name_label)) },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.group_new_members_hint),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            GroupMemberPicker(
                contacts = contacts,
                selectedHashes = selected,
                onToggle = { hash ->
                    selected = if (hash in selected) selected - hash else selected + hash
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
