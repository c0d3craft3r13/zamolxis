package network.zamolxis.app.rns.backend.kt

import network.zamolxis.app.rns.api.model.ConversationLinkResult
import network.zamolxis.app.rns.api.model.DeliveryMethod
import network.zamolxis.app.rns.api.model.DeliveryStatusUpdate
import network.zamolxis.app.rns.api.model.DiscoveredInterface
import network.zamolxis.app.rns.api.model.FailedInterface
import network.zamolxis.app.rns.api.model.IconAppearance
import network.zamolxis.app.rns.api.model.MessageReceipt
import network.zamolxis.app.rns.api.model.PropagationState
import network.zamolxis.app.rns.api.model.ReceivedMessage
import network.zamolxis.app.rns.api.model.VoiceCallState

/** Type aliases to disambiguate reticulum-kt types from Zamolxis model types. */
internal typealias NativeIdentity = network.reticulum.identity.Identity
internal typealias NativeDestination = network.reticulum.destination.Destination
internal typealias NativeDestinationType = network.reticulum.common.DestinationType
internal typealias NativeDeliveryMethod = network.reticulum.lxmf.DeliveryMethod
