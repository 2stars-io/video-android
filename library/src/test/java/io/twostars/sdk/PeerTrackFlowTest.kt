package io.twostars.sdk

import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack

/**
 * The track [Peer] mutators flow through `MutableStateFlow` so that a
 * UI consumer (`peer.videoTrack.collect { ... }`) can observe arrival
 * and removal as state transitions.
 *
 * The WebRTC track classes are mocked since constructing real ones
 * pulls in libwebrtc.so. We're testing the flow plumbing, not WebRTC
 * itself.
 */
class PeerTrackFlowTest {

    @Test fun `videoTrack starts null and updates on setVideoTrack`() = runTest {
        val peer = Peer("p1", "Alice", joinedAtMs = 0L)
        assertNull(peer.videoTrack.value)
        val track = mockk<VideoTrack>()
        peer.setVideoTrack(track)
        assertSame(track, peer.videoTrack.value)
        assertSame(track, peer.videoTrack.first())
    }

    @Test fun `audioTrack and videoTrack are independent`() = runTest {
        val peer = Peer("p1", null, 0L)
        val v = mockk<VideoTrack>()
        val a = mockk<AudioTrack>()
        peer.setVideoTrack(v)
        peer.setAudioTrack(a)
        assertSame(v, peer.videoTrack.value)
        assertSame(a, peer.audioTrack.value)
    }

    @Test fun `clearTracks resets every flow to null`() = runTest {
        val peer = Peer("p1", null, 0L)
        peer.setVideoTrack(mockk())
        peer.setAudioTrack(mockk())
        peer.setScreenTrack(mockk())
        peer.clearTracks()
        assertNull(peer.videoTrack.value)
        assertNull(peer.audioTrack.value)
        assertNull(peer.screenTrack.value)
    }

    // E8 — videoEnabled / audioEnabled flags. Default true so consumers
    // don't flash an avatar placeholder on first render before the
    // first state broadcast lands. Updates flow reactively.

    @Test fun `videoEnabled defaults to true and updates`() = runTest {
        val peer = Peer("p1", "Alice", 0L)
        org.junit.Assert.assertTrue(peer.videoEnabled.value)
        peer.setVideoEnabled(false)
        org.junit.Assert.assertFalse(peer.videoEnabled.value)
        peer.setVideoEnabled(true)
        org.junit.Assert.assertTrue(peer.videoEnabled.value)
    }

    @Test fun `audioEnabled defaults to true and is independent of videoEnabled`() = runTest {
        val peer = Peer("p1", "Alice", 0L)
        org.junit.Assert.assertTrue(peer.audioEnabled.value)
        peer.setAudioEnabled(false)
        org.junit.Assert.assertFalse(peer.audioEnabled.value)
        // Touching audio doesn't affect video.
        org.junit.Assert.assertTrue(peer.videoEnabled.value)
    }
}
