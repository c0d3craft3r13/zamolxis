package network.zamolxis.app.rns.api.util

import network.zamolxis.app.rns.api.model.ReceivedMessage
import org.json.JSONObject

/**
 * Whether an inbound LXMessage should appear as a chat bubble in the
 * conversation UI.
 *
 * Returns true when the message carries any user-visible payload:
 *   - non-blank text content
 *   - an image       (`fields[0x06]` = [FIELD_IMAGE])
 *   - file attachments (`fields[0x05]` = [LxmfFields.FIELD_FILE_ATTACHMENTS])
 *   - audio          (`fields[0x07]` = [LxmfFields.FIELD_AUDIO])
 *   - hybrid-sealed content (`fields[0x51]` = [LxmfFields.FIELD_SEALED_CONTENT])
 *
 * The sealed-content case is not optional. A post-quantum sealed message puts
 * its text in `fields[0x51]` and leaves the LXMF content slot empty, so without
 * it every sealed message looks like a side-channel frame and is dropped here —
 * before `MessageCollector` ever gets to open it. The sender still sees its own
 * copy and a delivery proof, so the failure is invisible on both ends.
 *
 * Returns false otherwise. Side-channel-only frames — telemetry-only
 * location shares (FIELD_TELEMETRY / FIELD_TELEMETRY_STREAM /
 * FIELD_CUSTOM_META), reaction-only events (FIELD_REACTION),
 * icon-only chatter from Sideband / MeshChat — all fall through to
 * false and are routed via their dedicated flows
 * (`RnsTelemetry.locationTelemetryFlow`, `_reactionReceivedFlow`,
 * etc.) instead of rendering as empty bubbles.
 *
 * **Lives in `:rns-api` so both backends share one implementation.**
 * Previously the Kotlin backend (NativeRnsBackendImpl) had a
 * `handleIncomingTelemetry → isLocationOnlyMessage` check before
 * `_messages.tryEmit(...)`, but the Python backend
 * (PythonEventBridge.handleLxmfDelivery) emitted unconditionally —
 * so every telemetry-only / reaction-only LXMessage landed in the
 * DB as an empty row and the UI rendered an empty bubble. Centring
 * the predicate here means adding a new user-visible field is one
 * edit, not two, and there can never be drift between the backends.
 */
fun ReceivedMessage.isUserVisibleChatMessage(): Boolean {
    if (content.isNotBlank()) return true
    val json = fieldsJson?.takeIf { it.isNotEmpty() } ?: return false
    return try {
        val parsed = JSONObject(json)
        parsed.has(LxmfFields.FIELD_IMAGE.toString()) ||
            parsed.has(LxmfFields.FIELD_FILE_ATTACHMENTS.toString()) ||
            parsed.has(LxmfFields.FIELD_AUDIO.toString()) ||
            parsed.has(LxmfFields.FIELD_SEALED_CONTENT.toString())
    } catch (_: Exception) {
        // Malformed fieldsJson with blank content — safer to drop
        // than render an empty bubble. The backend logs the parse
        // failure separately.
        false
    }
}
