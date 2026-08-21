package network.zamolxis.app.ui.screens.settings.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import network.zamolxis.app.R

/**
 * The licence, readable without a network.
 *
 * The About card used to link the licence out to GitHub. That is fine until
 * the person who most needs to read it is the one this app is built for —
 * somewhere with no route to GitHub, holding a build they were handed
 * offline. MPL 2.0 §3.2(a) expects the licence to travel *with* the thing it
 * covers, so the text is bundled as a raw resource and rendered here.
 *
 * The upstream notice sits above it rather than below: §3.3 keeps Columba's
 * attribution attached to this fork for as long as the fork exists, and a
 * notice nobody scrolls to is not much of a notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licenseText =
        remember {
            runCatching {
                context.resources
                    .openRawResource(R.raw.license_mpl2)
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse { "" }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.licenses_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section(stringResource(R.string.licenses_this_app_title)) {
                    Body(stringResource(R.string.licenses_this_app_body))
                }

                HorizontalDivider()

                Section(stringResource(R.string.licenses_upstream_title)) {
                    Body(stringResource(R.string.licenses_upstream_body))
                }

                HorizontalDivider()

                Section(stringResource(R.string.licenses_components_title)) {
                    Body(stringResource(R.string.licenses_components_body))
                }

                HorizontalDivider()

                Section(stringResource(R.string.licenses_full_text_title)) {
                    // Monospaced: the licence is a legal document with its own
                    // line breaks and numbering, and reflowing it into a
                    // proportional face makes the clause structure harder to follow.
                    Text(
                        text = licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
