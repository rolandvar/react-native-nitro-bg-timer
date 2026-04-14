package com.margelo.nitro.backgroundtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackgroundTimerCoreTest {

  /**
   * Test scheduler that just records which runnables are pending.
   * No real timing — `runAll()` fires everything synchronously so tests stay deterministic.
   */
  private class TestScheduler : TimerScheduler {
    private val lock = Any()
    private val pending = LinkedHashSet<Runnable>()

    override fun schedule(runnable: Runnable, delayMs: Long) {
      synchronized(lock) { pending.add(runnable) }
    }

    override fun cancel(runnable: Runnable) {
      synchronized(lock) { pending.remove(runnable) }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    // Mimics Android Handler: running a scheduled runnable removes it from the queue.
    // setInterval's runnable re-schedules itself during run(), which will re-add it.
    fun runAll() {
      val snapshot = synchronized(lock) {
        val s = pending.toList()
        pending.clear()
        s
      }
      snapshot.forEach { it.run() }
    }
  }

  /**
   * Mimics Android's PowerManager.WakeLock under-lock behavior:
   * throws RuntimeException if `release()` is called while not held.
   * This way the test catches any regression that would crash on-device.
   */
  private class FakeWakeLock : TimerWakeLock {
    private val lock = Any()
    private var held = false

    override val isHeld: Boolean
      get() = synchronized(lock) { held }

    override fun acquire() {
      synchronized(lock) { held = true }
    }

    override fun release() {
      synchronized(lock) {
        if (!held) throw RuntimeException("WakeLock under-locked")
        held = false
      }
    }
  }

  // --- Race test (regression guard for issue #1) ---

  @Test
  fun concurrent_setTimeout_same_id_leaves_exactly_one_pending_runnable() {
    val iterations = 2000
    val threadCount = 8
    val executor = Executors.newFixedThreadPool(threadCount)

    try {
      repeat(iterations) { iter ->
        val scheduler = TestScheduler()
        val wakeLock = FakeWakeLock()
        val core = BackgroundTimerCore(scheduler, wakeLock)

        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val failures = AtomicInteger(0)

        repeat(threadCount) {
          executor.submit {
            try {
              ready.countDown()
              start.await()
              // Large duration so nothing fires mid-test.
              core.setTimeout(1.0, 10_000.0) { /* no-op */ }
            } catch (t: Throwable) {
              failures.incrementAndGet()
            } finally {
              done.countDown()
            }
          }
        }

        assertTrue("iteration $iter: threads failed to arm", ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue("iteration $iter: threads did not complete", done.await(5, TimeUnit.SECONDS))

        assertEquals("iteration $iter: thread(s) threw", 0, failures.get())

        val pending = scheduler.pendingCount()
        assertEquals(
          "iteration $iter: race leaked runnables — expected 1 pending, got $pending",
          1,
          pending
        )
        assertTrue(
          "iteration $iter: wake lock should still be held while a timer is pending",
          wakeLock.isHeld
        )
      }
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun concurrent_setInterval_same_id_leaves_exactly_one_pending_runnable() {
    val iterations = 2000
    val threadCount = 8
    val executor = Executors.newFixedThreadPool(threadCount)

    try {
      repeat(iterations) { iter ->
        val scheduler = TestScheduler()
        val wakeLock = FakeWakeLock()
        val core = BackgroundTimerCore(scheduler, wakeLock)

        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val failures = AtomicInteger(0)

        repeat(threadCount) {
          executor.submit {
            try {
              ready.countDown()
              start.await()
              core.setInterval(1.0, 10_000.0) { /* no-op */ }
            } catch (t: Throwable) {
              failures.incrementAndGet()
            } finally {
              done.countDown()
            }
          }
        }

        assertTrue("iteration $iter: threads failed to arm", ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue("iteration $iter: threads did not complete", done.await(5, TimeUnit.SECONDS))

        assertEquals("iteration $iter: thread(s) threw", 0, failures.get())

        val pending = scheduler.pendingCount()
        assertEquals(
          "iteration $iter: race leaked runnables — expected 1 pending, got $pending",
          1,
          pending
        )
      }
    } finally {
      executor.shutdownNow()
    }
  }

  // --- Concurrent mixed setTimeout/clearTimeout also stays invariant ---

  @Test
  fun concurrent_setTimeout_and_clearTimeout_never_leaves_stale_state() {
    val iterations = 1000
    val executor = Executors.newFixedThreadPool(4)

    try {
      repeat(iterations) { iter ->
        val scheduler = TestScheduler()
        val wakeLock = FakeWakeLock()
        val core = BackgroundTimerCore(scheduler, wakeLock)

        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val failures = AtomicInteger(0)

        fun runOp(op: () -> Unit) {
          executor.submit {
            try {
              ready.countDown()
              start.await()
              op()
            } catch (t: Throwable) {
              failures.incrementAndGet()
            } finally {
              done.countDown()
            }
          }
        }

        runOp { core.setTimeout(1.0, 10_000.0) { } }
        runOp { core.setTimeout(1.0, 10_000.0) { } }
        runOp { core.clearTimeout(1.0) }
        runOp { core.setTimeout(1.0, 10_000.0) { } }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("iteration $iter: thread(s) threw", 0, failures.get())

        // Final state must be coherent: pending count is 0 or 1 (never more),
        // and wake lock is held iff pending > 0.
        val pending = scheduler.pendingCount()
        assertTrue(
          "iteration $iter: pending count out of range: $pending",
          pending in 0..1
        )
        assertEquals(
          "iteration $iter: wake lock state inconsistent with pending=$pending",
          pending > 0,
          wakeLock.isHeld
        )
      }
    } finally {
      executor.shutdownNow()
    }
  }

  // --- Basic sanity tests ---

  @Test
  fun setTimeout_fires_callback_and_releases_wake_lock() {
    val scheduler = TestScheduler()
    val wakeLock = FakeWakeLock()
    val core = BackgroundTimerCore(scheduler, wakeLock)
    val fired = AtomicInteger(0)

    core.setTimeout(42.0, 0.0) { fired.incrementAndGet() }

    assertTrue(wakeLock.isHeld)
    assertEquals(1, scheduler.pendingCount())

    scheduler.runAll()

    assertEquals(1, fired.get())
    assertEquals(0, scheduler.pendingCount())
    assertFalse(wakeLock.isHeld)
  }

  @Test
  fun clearTimeout_releases_wake_lock_when_last_timer_cleared() {
    val scheduler = TestScheduler()
    val wakeLock = FakeWakeLock()
    val core = BackgroundTimerCore(scheduler, wakeLock)

    core.setTimeout(1.0, 10_000.0) { }
    assertTrue(wakeLock.isHeld)

    core.clearTimeout(1.0)

    assertFalse(wakeLock.isHeld)
    assertEquals(0, scheduler.pendingCount())
  }

  @Test
  fun multiple_timers_hold_wake_lock_until_last_one_clears() {
    val scheduler = TestScheduler()
    val wakeLock = FakeWakeLock()
    val core = BackgroundTimerCore(scheduler, wakeLock)

    core.setTimeout(1.0, 10_000.0) { }
    core.setTimeout(2.0, 10_000.0) { }
    core.setInterval(3.0, 10_000.0) { }

    assertTrue(wakeLock.isHeld)

    core.clearTimeout(1.0)
    assertTrue(wakeLock.isHeld)

    core.clearTimeout(2.0)
    assertTrue(wakeLock.isHeld)

    core.clearInterval(3.0)
    assertFalse(wakeLock.isHeld)
  }

  @Test
  fun cleanup_clears_everything_without_throwing_when_already_released() {
    val scheduler = TestScheduler()
    val wakeLock = FakeWakeLock()
    val core = BackgroundTimerCore(scheduler, wakeLock)

    core.setTimeout(1.0, 10_000.0) { }
    core.clearTimeout(1.0)

    // Wake lock already released — cleanup must be a no-op on the wake lock, not throw.
    core.cleanup()

    assertFalse(wakeLock.isHeld)
    assertEquals(0, scheduler.pendingCount())
  }
}
