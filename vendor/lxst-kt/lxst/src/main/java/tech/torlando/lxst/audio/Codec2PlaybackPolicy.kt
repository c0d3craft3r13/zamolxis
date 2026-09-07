/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

/** Playback policy shared by the Kotlin and native receive paths. */
internal object Codec2PlaybackPolicy {
    /**
     * Python LXST starts playback after one decoded packet. These durations are
     * the canonical ULBW, VLBW, and LBW packet durations respectively.
     */
    fun usesSinglePacketBuffer(frameTimeMs: Long): Boolean =
        when (frameTimeMs) {
            400L, 320L, 200L -> true
            else -> false
        }
}
