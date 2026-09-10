/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package network.zamolxis.app.rns.backend.kt

import android.util.Log
import network.zamolxis.app.rns.api.util.toHex
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.transport.Transport
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.telephone.NetworkTransport

/**
 * Native Kotlin implementation of [NetworkTransport] for LXST telephony.
 *
 * Replaces [PythonNetworkTransport] by using reticulum-kt [Link] directly,
 * eliminating the Python GIL from the voice packet path. Audio packets are
 * sent/received over the Reticulum link without any Python intermediary.
 *
 * This is the critical change that fixes voice call latency caused by GIL
 * contention between the audio transmit loop and announce processing.
 */
class NativeNetworkTransport : NetworkTransport {
    companion object {
        private const val TAG = "NativeNetworkTransport"
        private const val LXST_APP_NAME = "lxst"
        private const val LXST_ASPECT = "telephony"

        /** How long to wait for a path before giving up on the call. */
        private const val PATH_RESOLVE_TIMEOUT_MS = 8_000L

        private const val PATH_POLL_MS = 100L

        /** Time allowed for a link to come up, generous for low-bandwidth paths. */
        private const val LINK_ESTABLISH_TIMEOUT_MS = 15_000L
    }

    // These fields are written from both coroutines (call lifecycle) and
    // Reticulum library callbacks (link established/closed, inbound packets)
    // running on different threads. Mark @Volatile so sendPacket / sendSignal /
    // handleLinkClosed observe the latest writes under the JVM memory model.
    @Volatile private var activeLink: Link? = null

    @Volatile private var packetCallback: ((ByteArray) -> Unit)? = null

    @Volatile private var signalCallback: ((Int) -> Unit)? = null

    @Volatile private var locallyClosingLink: Link? = null

    /**
     * Local identity used to identify ourselves to the remote peer.
     *
     * Must be set before any call is made. When STATUS_AVAILABLE is received from the
     * callee (outgoing call path), we call [Link.identify] to send our identity.
     * This mirrors Python call_manager.__packet_received's STATUS_AVAILABLE handler.
     */
    @Volatile private var localIdentity: Identity? = null

    fun setLocalIdentity(identity: Identity) {
        localIdentity = identity
    }

    private fun handleLinkClosed(
        link: Link,
        reason: Int,
        logPrefix: String,
    ) {
        Log.i(TAG, "$logPrefix closed: reason=$reason")
        val wasLocalTeardown = locallyClosingLink === link
        if (wasLocalTeardown) {
            locallyClosingLink = null
        }
        if (activeLink === link) {
            activeLink = null
        }

        // Mirror the old Python path: remote link close notifies Telephone with
        // STATUS_AVAILABLE so the state machine tears down the call UI/audio.
        if (!wasLocalTeardown) {
            signalCallback?.invoke(Signalling.STATUS_AVAILABLE)
        }
    }

    private fun installLinkCallbacks(link: Link) {
        link.setPacketCallback { data, _ ->
            handleIncomingPacket(data)
        }
        link.setLinkClosedCallback { l ->
            handleLinkClosed(link, l.teardownReason, "Link")
        }
    }

    override val isLinkActive: Boolean
        get() = activeLink?.status == LinkConstants.ACTIVE

    override suspend fun establishLink(destinationHash: ByteArray): Boolean =
        try {
            val identity = recallOrRequestIdentity(destinationHash)
            if (identity == null) {
                false
            } else {
                establishLinkToIdentity(identity, destinationHash)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing link", e)
            false
        }

    private suspend fun recallOrRequestIdentity(destinationHash: ByteArray): Identity? {
        val recalled =
            Identity.recall(destinationHash)
                ?: Identity.recallByIdentityHash(destinationHash)
        if (recalled != null) return recalled

        Log.w(TAG, "Cannot establish link: identity not known for ${destinationHash.toHex().take(16)}")
        Transport.requestPath(destinationHash)
        kotlinx.coroutines.delay(3000)
        val retried =
            Identity.recall(destinationHash)
                ?: Identity.recallByIdentityHash(destinationHash)
        if (retried == null) {
            Log.e(TAG, "Identity still not known after path request")
        }
        return retried
    }

    private suspend fun establishLinkToIdentity(
        identity: Identity,
        destHash: ByteArray,
    ): Boolean {
        val dest =
            Destination.create(
                identity,
                DestinationDirection.OUT,
                DestinationType.SINGLE,
                LXST_APP_NAME,
                LXST_ASPECT,
            )

        // The path has to be to the telephony destination's own hash, not to the
        // one that was dialled. `lxmf.delivery` and `lxst.telephony` are separate
        // destinations with separate hashes, and knowing how to reach someone's
        // messages says nothing about reaching their phone.
        //
        // This used to be carried by the telephony announce, which published a
        // path for everyone whether they were calling or not. Asking for one at
        // dial time gets the same result from the callee alone: a registered
        // destination answers a path request with a path response, so the path
        // exists for the length of the call rather than being broadcast every few
        // hours to the whole mesh.
        val hadCachedPath = Transport.hasPath(dest.hash)
        if (!hadCachedPath && !awaitPath(dest.hash)) {
            Log.w(TAG, "No path to the telephony destination after ${PATH_RESOLVE_TIMEOUT_MS}ms")
            return false
        }

        var link = openLink(dest)

        // A cached path can outlive the interface that taught it. When the phone
        // drops to BLE-only, a TCP-learned entry stays in the path table, hasPath
        // keeps saying yes, and every link request goes into a route that is no
        // longer there — the callee sees nothing at all. Expire it, rediscover
        // over whatever interface is still up, and try once more. The Python
        // backend has carried this since it was observed on two phones.
        if (link == null && hadCachedPath) {
            Log.w(TAG, "Cached path to telephony looks stale — expiring and rediscovering")
            Transport.expirePath(dest.hash)
            link = if (awaitPath(dest.hash)) openLink(dest) else null
        }

        if (link == null) {
            Log.w(TAG, "Could not open a link to the telephony destination")
            activeLink = null
            return false
        }

        Log.i(TAG, "Link active to ${destHash.toHex().take(16)}")

        // Identify proactively as soon as the link becomes active.
        // In theory the callee's STATUS_AVAILABLE should trigger this, but on real
        // devices that first 1-byte signal can race with callback installation on
        // either side. Sending LINKIDENTIFY immediately avoids that handshake race
        // while remaining protocol-correct: only the initiator may identify, and
        // Link.identify() already enforces ACTIVE status.
        val localId = localIdentity
        if (localId != null) {
            Log.i(TAG, "Proactive identify sent=${link.identify(localId)}")
        } else {
            Log.w(TAG, "Link became active but localIdentity was null")
        }
        return true
    }

    /**
     * Ask for a path to [destinationHash] and wait for one to arrive.
     *
     * The callee answers this itself: a destination registered on that device
     * responds to a path request with a path response, even if it has never
     * announced. That is what lets telephony stay off the air until someone
     * actually calls.
     */
    private suspend fun awaitPath(destinationHash: ByteArray): Boolean {
        Transport.requestPath(destinationHash)
        val deadline = System.currentTimeMillis() + PATH_RESOLVE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (Transport.hasPath(destinationHash)) return true
            kotlinx.coroutines.delay(PATH_POLL_MS)
        }
        return false
    }

    /** Open a link and wait for it to come up, or null if it does not. */
    private suspend fun openLink(dest: Destination): Link? {
        val link =
            Link.create(
                destination = dest,
                establishedCallback = { l ->
                    Log.i(TAG, "Link established: rtt=${l.rtt}ms")
                },
            )
        activeLink = link
        installLinkCallbacks(link)

        val deadline = System.currentTimeMillis() + LINK_ESTABLISH_TIMEOUT_MS
        while (link.status != LinkConstants.ACTIVE &&
            link.status != LinkConstants.CLOSED &&
            System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(PATH_POLL_MS)
        }
        return link.takeIf { it.status == LinkConstants.ACTIVE }
    }

    override fun teardownLink() {
        activeLink?.let { link ->
            Log.i(TAG, "Tearing down link")
            locallyClosingLink = link
            link.teardown()
        }
        activeLink = null
    }

    override fun sendPacket(encodedFrame: ByteArray) {
        val link = activeLink ?: return
        if (link.status != LinkConstants.ACTIVE) return
        // Wrap audio in msgpack {FIELD_FRAMES(1): binary} for Python interop
        val packer =
            org.msgpack.core.MessagePack
                .newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packInt(0x01) // FIELD_FRAMES
        packer.packBinaryHeader(encodedFrame.size)
        packer.writePayload(encodedFrame)
        link.send(packer.toByteArray())
    }

    override fun sendSignal(signal: Int) {
        val link = activeLink ?: return
        if (link.status != LinkConstants.ACTIVE) return
        // Wrap signal in msgpack {FIELD_SIGNALLING(0): [signal]} for Python interop
        val packer =
            org.msgpack.core.MessagePack
                .newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packInt(0x00) // FIELD_SIGNALLING
        packer.packArrayHeader(1)
        packer.packInt(signal)
        link.send(packer.toByteArray())
    }

    override fun setPacketCallback(callback: (ByteArray) -> Unit) {
        packetCallback = callback
    }

    override fun setSignalCallback(callback: (Int) -> Unit) {
        signalCallback = callback
    }

    /**
     * Accept an inbound link from an incoming caller.
     *
     * Called by [NativeCallManager] after the caller's identity has been verified via
     * [Link.setRemoteIdentifiedCallback]. Wires up packet and closed callbacks on the
     * link so audio and signals flow through this transport.
     *
     * @param link The fully-established inbound link from the caller
     */
    fun acceptInboundLink(link: Link) {
        Log.i(TAG, "Accepting inbound call link: ${link.linkId.toHex().take(16)}")
        activeLink = link
        link.setPacketCallback { data, _ ->
            handleIncomingPacket(data)
        }
        link.setLinkClosedCallback { l ->
            handleLinkClosed(link, l.teardownReason, "Inbound link")
        }
    }

    private fun handleIncomingPacket(data: ByteArray) {
        if (data.isEmpty()) return

        // Python LXST sends data as msgpack maps:
        //   {0: [signal_value, ...]} for signals (FIELD_SIGNALLING=0x00)
        //   {1: binary_audio}        for audio frames (FIELD_FRAMES=0x01)
        // Also handle raw 1-byte signals for Kotlin↔Kotlin calls.
        val unpacked =
            try {
                org.msgpack.core.MessagePack
                    .newDefaultUnpacker(data)
                    .unpackValue()
            } catch (_: Exception) {
                null
            }

        if (unpacked != null && unpacked.isMapValue) {
            val map = unpacked.asMapValue().map()
            val signallingKey =
                org.msgpack.value.ValueFactory
                    .newInteger(0x00)
            val framesKey =
                org.msgpack.value.ValueFactory
                    .newInteger(0x01)

            val signalling = map[signallingKey]
            val frames = map[framesKey]

            if (signalling != null && signalling.isArrayValue) {
                for (sig in signalling.asArrayValue()) {
                    val signal = sig.asIntegerValue().toInt()
                    Log.d(TAG, "Inbound signal 0x${signal.toString(16)}")
                    // Note: we identify proactively after link establishment
                    // (see establishLinkToIdentity), so no need to re-identify
                    // on STATUS_AVAILABLE. Double-identify confuses Python Sideband.
                    signalCallback?.invoke(signal)
                }
            }

            if (frames != null && frames.isBinaryValue) {
                packetCallback?.invoke(frames.asBinaryValue().asByteArray())
            }
        } else if (data.size == 1) {
            val signal = data[0].toInt() and 0xFF
            Log.d(TAG, "Inbound raw signal 0x${signal.toString(16)}")
            signalCallback?.invoke(signal)
        } else {
            packetCallback?.invoke(data)
        }
    }
}
