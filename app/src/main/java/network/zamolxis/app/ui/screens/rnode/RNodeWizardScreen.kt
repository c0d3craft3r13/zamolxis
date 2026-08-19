package network.zamolxis.app.ui.screens.rnode

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import network.zamolxis.app.R
import network.zamolxis.app.ui.components.WizardBottomBar
import network.zamolxis.app.viewmodel.RNodeWizardViewModel
import network.zamolxis.app.viewmodel.WizardStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RNodeWizardScreen(
    editingInterfaceId: Long? = null,
    preselectedConnectionType: String? = null,
    preselectedUsbDeviceId: Int? = null,
    preselectedUsbVendorId: Int? = null,
    preselectedUsbProductId: Int? = null,
    preselectedUsbDeviceName: String? = null,
    preselectedLoraFrequency: Long? = null,
    preselectedLoraBandwidth: Int? = null,
    preselectedLoraSf: Int? = null,
    preselectedLoraCr: Int? = null,
    transportMode: Boolean = false,
    onNavigateBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: RNodeWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Enable transport mode if requested
    LaunchedEffect(transportMode) {
        if (transportMode) {
            viewModel.enableTransportMode()
        }
    }

    // Handle system back button - go to previous step or exit wizard
    BackHandler {
        if (state.currentStep == WizardStep.DEVICE_DISCOVERY) {
            onNavigateBack()
        } else {
            viewModel.goToPreviousStep()
        }
    }

    // Load existing config if editing
    LaunchedEffect(editingInterfaceId) {
        if (editingInterfaceId != null && editingInterfaceId >= 0) {
            viewModel.loadExistingConfig(editingInterfaceId)
        }
    }

    // Apply USB pre-selection if provided
    LaunchedEffect(preselectedConnectionType, preselectedUsbDeviceId) {
        @Suppress("ComplexCondition") // All 5 parameters must be present for USB pre-selection
        if (preselectedConnectionType == "usb" &&
            preselectedUsbDeviceId != null &&
            preselectedUsbVendorId != null &&
            preselectedUsbProductId != null &&
            preselectedUsbDeviceName != null
        ) {
            viewModel.preselectUsbDevice(
                deviceId = preselectedUsbDeviceId,
                vendorId = preselectedUsbVendorId,
                productId = preselectedUsbProductId,
                deviceName = preselectedUsbDeviceName,
            )
        }
    }

    // Apply LoRa params pre-selection if provided (from discovered interfaces)
    LaunchedEffect(preselectedLoraFrequency, preselectedLoraBandwidth, preselectedLoraSf, preselectedLoraCr) {
        val hasLoraParams =
            listOfNotNull(
                preselectedLoraFrequency,
                preselectedLoraBandwidth,
                preselectedLoraSf,
                preselectedLoraCr,
            ).isNotEmpty()
        if (hasLoraParams) {
            viewModel.setInitialRadioParams(
                frequency = preselectedLoraFrequency,
                bandwidth = preselectedLoraBandwidth,
                spreadingFactor = preselectedLoraSf,
                codingRate = preselectedLoraCr,
            )
        }
    }

    // Handle save success
    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            viewModel.consumeSaveSuccess()
            onComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.currentStep) {
                            WizardStep.DEVICE_DISCOVERY ->
                                when {
                                    state.transportMode -> stringResource(R.string.rnode_wiz_title_configure_transport)
                                    state.isEditMode -> stringResource(R.string.rnode_wiz_title_change_device)
                                    else -> stringResource(R.string.rnode_wiz_title_select_device)
                                }
                            WizardStep.REGION_SELECTION -> stringResource(R.string.rnode_wiz_title_choose_region)
                            WizardStep.MODEM_PRESET -> stringResource(R.string.rnode_wiz_title_modem_preset)
                            WizardStep.FREQUENCY_SLOT -> stringResource(R.string.rnode_wiz_title_frequency_slot)
                            WizardStep.REVIEW_CONFIGURE ->
                                if (state.transportMode) {
                                    stringResource(R.string.rnode_wiz_title_review_transport)
                                } else {
                                    stringResource(R.string.rnode_wiz_title_review)
                                }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.currentStep == WizardStep.DEVICE_DISCOVERY) {
                                onNavigateBack()
                            } else {
                                viewModel.goToPreviousStep()
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            WizardBottomBar(
                currentStepIndex = state.currentStep.ordinal,
                totalSteps = WizardStep.entries.size,
                buttonText =
                    when (state.currentStep) {
                        WizardStep.REVIEW_CONFIGURE ->
                            when {
                                state.transportMode -> stringResource(R.string.rnode_wiz_enable_transport)
                                state.isEditMode -> stringResource(R.string.rnode_wiz_update)
                                else -> stringResource(R.string.common_save)
                            }
                        else -> stringResource(R.string.rnode_wiz_next)
                    },
                canProceed = viewModel.canProceed(),
                isSaving = state.isSaving || state.transportConfiguring,
                onButtonClick = {
                    if (state.currentStep == WizardStep.REVIEW_CONFIGURE) {
                        if (state.transportMode) {
                            viewModel.applyTransportMode()
                        } else {
                            viewModel.saveConfiguration()
                        }
                    } else {
                        viewModel.goToNextStep()
                    }
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { paddingValues ->
        AnimatedContent(
            targetState = state.currentStep,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                } else {
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                }
            },
            label = "wizard_step",
        ) { step ->
            when (step) {
                WizardStep.DEVICE_DISCOVERY -> DeviceDiscoveryStep(viewModel)
                WizardStep.REGION_SELECTION -> RegionSelectionStep(viewModel)
                WizardStep.MODEM_PRESET -> ModemPresetStep(viewModel)
                WizardStep.FREQUENCY_SLOT -> FrequencySlotStep(viewModel)
                WizardStep.REVIEW_CONFIGURE -> ReviewConfigStep(viewModel)
            }
        }
    }

    // Error dialog
    state.saveError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearSaveError() },
            title = { Text(stringResource(R.string.rnode_wiz_error)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSaveError() }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }

    // Transport config error dialog
    state.transportConfigError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearTransportConfigError() },
            title = { Text(stringResource(R.string.rnode_wiz_transport_failed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearTransportConfigError() }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}
