package network.zamolxis.app.rns.backend.py

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The stamp-generation cancellation token is polled from up to 8 workers every 256
 * candidates. Unthrottled that is tens of thousands of Chaquopy round-trips a second,
 * each reacquiring the GIL out from under the RNS reactor. These tests pin the
 * throttling that bounds it.
 */
class ThrottledLatchingPredicateTest {
    @Test
    fun `polls once per interval regardless of how often it is asked`() {
        val clock = AtomicLong(0L)
        val polls = AtomicInteger(0)
        val predicate = throttledLatchingPredicate(250L, { clock.get() }) {
            polls.incrementAndGet()
            false
        }

        repeat(10_000) { predicate() }

        assertEquals("Within one interval the source must be polled exactly once", 1, polls.get())
    }

    @Test
    fun `polls again once the interval has elapsed`() {
        val clock = AtomicLong(0L)
        val polls = AtomicInteger(0)
        val predicate = throttledLatchingPredicate(250L, { clock.get() }) {
            polls.incrementAndGet()
            false
        }

        predicate()
        clock.set(249L)
        predicate()
        assertEquals("Still inside the window", 1, polls.get())

        clock.set(250L)
        predicate()
        assertEquals("Window elapsed, one more poll allowed", 2, polls.get())
    }

    @Test
    fun `true latches and the source is never polled again`() {
        val clock = AtomicLong(0L)
        val polls = AtomicInteger(0)
        val predicate = throttledLatchingPredicate(250L, { clock.get() }) {
            polls.incrementAndGet()
            true
        }

        assertTrue(predicate())
        clock.set(10_000L)
        repeat(100) { assertTrue(predicate()) }

        assertEquals("A latched result must not be re-polled", 1, polls.get())
    }

    @Test
    fun `first call polls immediately rather than waiting out an interval`() {
        val predicate = throttledLatchingPredicate(250L, { 0L }) { true }

        assertTrue("Cancellation already set before the first poll must be seen at once", predicate())
    }

    @Test
    fun `concurrent callers within one interval produce a single poll`() {
        val workers = 8
        val clock = AtomicLong(0L)
        val polls = AtomicInteger(0)
        val predicate = throttledLatchingPredicate(250L, { clock.get() }) {
            polls.incrementAndGet()
            false
        }

        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val threads = (0 until workers).map {
            Thread {
                start.await()
                repeat(1_000) { predicate() }
                done.countDown()
            }.apply { start() }
        }

        start.countDown()
        assertTrue("Workers should finish promptly", done.await(30, TimeUnit.SECONDS))
        threads.forEach { it.join() }

        assertEquals("compareAndSet must let exactly one worker through per interval", 1, polls.get())
        assertFalse(predicate())
    }
}
