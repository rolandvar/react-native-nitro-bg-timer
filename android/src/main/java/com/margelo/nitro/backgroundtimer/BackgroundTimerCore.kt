package com.margelo.nitro.backgroundtimer

internal interface TimerScheduler {
  fun schedule(runnable: Runnable, delayMs: Long)
  fun cancel(runnable: Runnable)
}

internal interface TimerWakeLock {
  val isHeld: Boolean
  fun acquire()
  fun release()
}

internal class BackgroundTimerCore(
  private val scheduler: TimerScheduler,
  private val wakeLock: TimerWakeLock,
  private val onError: (tag: String, message: String, throwable: Throwable?) -> Unit = { _, _, _ -> }
) {
  private val lock = Any()
  private val timeoutRunnables = HashMap<Int, Runnable>()
  private val intervalRunnables = HashMap<Int, Runnable>()

  private fun acquireWakeLockLocked() {
    if (!wakeLock.isHeld) {
      wakeLock.acquire()
    }
  }

  private fun releaseWakeLockIfNeededLocked() {
    if (timeoutRunnables.isEmpty() && intervalRunnables.isEmpty() && wakeLock.isHeld) {
      try {
        wakeLock.release()
      } catch (e: RuntimeException) {
        onError("NitroBackgroundTimer", "WakeLock release threw: ${e.message}", e)
      }
    }
  }

  fun setTimeout(id: Double, duration: Double, callback: (Double) -> Unit): Double {
    val intId = id.toInt()
    val delayMs = duration.toLong()

    synchronized(lock) {
      timeoutRunnables.remove(intId)?.let { scheduler.cancel(it) }

      val runnable = Runnable {
        try {
          callback(id)
        } catch (e: Exception) {
          onError("NitroBackgroundTimer", "Callback error in setTimeout($id): ${e.message}", e)
        }
        synchronized(lock) {
          timeoutRunnables.remove(intId)
          releaseWakeLockIfNeededLocked()
        }
      }

      acquireWakeLockLocked()
      timeoutRunnables[intId] = runnable
      scheduler.schedule(runnable, delayMs)
    }
    return id
  }

  fun clearTimeout(id: Double) {
    val intId = id.toInt()
    synchronized(lock) {
      timeoutRunnables.remove(intId)?.let { scheduler.cancel(it) }
      releaseWakeLockIfNeededLocked()
    }
  }

  fun setInterval(id: Double, interval: Double, callback: (Double) -> Unit): Double {
    val intId = id.toInt()
    val intervalMs = interval.toLong()

    synchronized(lock) {
      intervalRunnables.remove(intId)?.let { scheduler.cancel(it) }

      val runnable = object : Runnable {
        override fun run() {
          try {
            callback(id)
          } catch (e: Exception) {
            onError("NitroBackgroundTimer", "Callback error in setInterval($id): ${e.message}", e)
          }
          scheduler.schedule(this, intervalMs)
        }
      }

      acquireWakeLockLocked()
      intervalRunnables[intId] = runnable
      scheduler.schedule(runnable, intervalMs)
    }
    return id
  }

  fun clearInterval(id: Double) {
    val intId = id.toInt()
    synchronized(lock) {
      intervalRunnables.remove(intId)?.let { scheduler.cancel(it) }
      releaseWakeLockIfNeededLocked()
    }
  }

  fun cleanup() {
    synchronized(lock) {
      timeoutRunnables.values.forEach { scheduler.cancel(it) }
      intervalRunnables.values.forEach { scheduler.cancel(it) }
      timeoutRunnables.clear()
      intervalRunnables.clear()
      if (wakeLock.isHeld) {
        try {
          wakeLock.release()
        } catch (e: RuntimeException) {
          onError("NitroBackgroundTimer", "WakeLock release threw during cleanup: ${e.message}", e)
        }
      }
    }
  }
}
