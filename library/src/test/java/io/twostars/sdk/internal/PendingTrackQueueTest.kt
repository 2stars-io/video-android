package io.twostars.sdk.internal

import io.mockk.mockk
import io.twostars.sdk.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.MediaStreamTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pins down the contract that `Room.onRemoteTrack` relies on:
 * FIFO arrival order, per-peer isolation, configurable cap with
 * overflow callback, forget/clear semantics, and thread-safety under
 * concurrent enqueue.
 *
 * Mocks `MediaStreamTrack` with `relaxed = true` because libwebrtc.so
 * isn't loaded in unit tests and the queue never inspects track
 * internals — it only routes references.
 */
class PendingTrackQueueTest {

    @Test fun `drain on empty queue returns empty list`() {
        val q = PendingTrackQueue()
        assertTrue(q.drain("alice").isEmpty())
    }

    @Test fun `enqueue then drain returns the inserted track`() {
        val q = PendingTrackQueue()
        val t = mockk<MediaStreamTrack>(relaxed = true)
        assertTrue(q.enqueue("alice", TrackKind.VIDEO, t))
        val drained = q.drain("alice")
        assertEquals(1, drained.size)
        assertEquals(TrackKind.VIDEO, drained[0].kind)
        assertSame(t, drained[0].track)
    }

    @Test fun `drain preserves FIFO arrival order`() {
        val q = PendingTrackQueue()
        val audio = mockk<MediaStreamTrack>(relaxed = true)
        val video = mockk<MediaStreamTrack>(relaxed = true)
        val screen = mockk<MediaStreamTrack>(relaxed = true)
        q.enqueue("alice", TrackKind.AUDIO, audio)
        q.enqueue("alice", TrackKind.VIDEO, video)
        q.enqueue("alice", TrackKind.SCREEN, screen)
        val drained = q.drain("alice")
        assertEquals(
            listOf(TrackKind.AUDIO, TrackKind.VIDEO, TrackKind.SCREEN),
            drained.map { it.kind },
        )
        assertSame(audio, drained[0].track)
        assertSame(video, drained[1].track)
        assertSame(screen, drained[2].track)
    }

    @Test fun `drain is per-peer isolated`() {
        val q = PendingTrackQueue()
        val aliceTrack = mockk<MediaStreamTrack>(relaxed = true)
        val bobTrack = mockk<MediaStreamTrack>(relaxed = true)
        q.enqueue("alice", TrackKind.VIDEO, aliceTrack)
        q.enqueue("bob", TrackKind.VIDEO, bobTrack)

        val aliceDrained = q.drain("alice")
        assertEquals(1, aliceDrained.size)
        assertSame(aliceTrack, aliceDrained[0].track)

        // Bob's queue is untouched by Alice's drain.
        val bobDrained = q.drain("bob")
        assertEquals(1, bobDrained.size)
        assertSame(bobTrack, bobDrained[0].track)
    }

    @Test fun `drain clears the entry — second drain returns empty`() {
        val q = PendingTrackQueue()
        q.enqueue("alice", TrackKind.VIDEO, mockk(relaxed = true))
        assertEquals(1, q.drain("alice").size)
        assertTrue(q.drain("alice").isEmpty())
    }

    @Test fun `cap drops overflow and fires onOverflow with offending kind`() {
        val overflows = mutableListOf<Pair<String, TrackKind>>()
        val q = PendingTrackQueue(
            maxPerPeer = 2,
            onOverflow = { pid, kind -> overflows.add(pid to kind) },
        )
        assertTrue(q.enqueue("alice", TrackKind.AUDIO, mockk(relaxed = true)))
        assertTrue(q.enqueue("alice", TrackKind.VIDEO, mockk(relaxed = true)))
        // Third attempt overflows.
        assertFalse(q.enqueue("alice", TrackKind.SCREEN, mockk(relaxed = true)))

        // Capped count remains at 2, overflow callback was called with
        // the dropped track's kind.
        assertEquals(2, q.drain("alice").size)
        assertEquals(1, overflows.size)
        assertEquals("alice" to TrackKind.SCREEN, overflows[0])
    }

    @Test fun `cap is per-peer — alice overflow does not affect bob`() {
        val q = PendingTrackQueue(maxPerPeer = 1)
        assertTrue(q.enqueue("alice", TrackKind.VIDEO, mockk(relaxed = true)))
        // Alice already at cap.
        assertFalse(q.enqueue("alice", TrackKind.AUDIO, mockk(relaxed = true)))
        // Bob is unaffected.
        assertTrue(q.enqueue("bob", TrackKind.VIDEO, mockk(relaxed = true)))
        assertEquals(1, q.drain("alice").size)
        assertEquals(1, q.drain("bob").size)
    }

    @Test fun `forget drops queued entries without binding`() {
        val q = PendingTrackQueue()
        q.enqueue("alice", TrackKind.VIDEO, mockk(relaxed = true))
        q.enqueue("alice", TrackKind.AUDIO, mockk(relaxed = true))
        q.forget("alice")
        assertTrue(q.drain("alice").isEmpty())
    }

    @Test fun `forget on unknown peer is a no-op`() {
        val q = PendingTrackQueue()
        q.forget("never-existed")
        assertTrue(q.drain("never-existed").isEmpty())
    }

    @Test fun `clear drops every peer's queue`() {
        val q = PendingTrackQueue()
        q.enqueue("alice", TrackKind.VIDEO, mockk(relaxed = true))
        q.enqueue("bob", TrackKind.AUDIO, mockk(relaxed = true))
        q.enqueue("carol", TrackKind.SCREEN, mockk(relaxed = true))
        q.clear()
        assertTrue(q.drain("alice").isEmpty())
        assertTrue(q.drain("bob").isEmpty())
        assertTrue(q.drain("carol").isEmpty())
        assertEquals(0, q.size())
    }

    @Test fun `size reflects total queued across peers`() {
        val q = PendingTrackQueue()
        assertEquals(0, q.size())
        q.enqueue("alice", TrackKind.VIDEO, mockk(relaxed = true))
        q.enqueue("alice", TrackKind.AUDIO, mockk(relaxed = true))
        q.enqueue("bob", TrackKind.VIDEO, mockk(relaxed = true))
        assertEquals(3, q.size())
        q.drain("alice")
        assertEquals(1, q.size())
        q.forget("bob")
        assertEquals(0, q.size())
    }

    /**
     * Two threads enqueueing the same participant concurrently must
     * not corrupt the underlying list (no ConcurrentModificationException,
     * no lost entries, no over-cap accumulation). The cap value is
     * exactly the number of entries we expect to land — if either
     * thread sees a stale view we'd either lose entries or exceed the
     * cap.
     */
    @Test fun `concurrent enqueue is thread-safe and respects the cap`() {
        val totalAttempts = 200
        val cap = 50
        val q = PendingTrackQueue(maxPerPeer = cap)
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(totalAttempts)
        repeat(totalAttempts) {
            pool.submit {
                ready.await()
                q.enqueue("alice", TrackKind.VIDEO, mockk(relaxed = true))
                done.countDown()
            }
        }
        ready.countDown()
        assertTrue("concurrent enqueue timed out", done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()

        val drained = q.drain("alice")
        assertEquals(
            "exactly the cap should be retained; cap=$cap got=${drained.size}",
            cap,
            drained.size,
        )
    }
}
