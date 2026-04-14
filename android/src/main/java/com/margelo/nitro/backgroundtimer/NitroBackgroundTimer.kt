package com.margelo.nitro.backgroundtimer

import android.annotation.SuppressLint
import com.facebook.proguard.annotations.DoNotStrip
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.margelo.nitro.NitroModules

@DoNotStrip
class NitroBackgroundTimer : HybridNitroBackgroundTimerSpec() {
  private val context = NitroModules.applicationContext
    ?: throw IllegalStateException("NitroModules.applicationContext is null")

  private val handler = Handler(Looper.getMainLooper())
  private val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager

  @SuppressLint("InvalidWakeLockTag")
  private val androidWakeLock: PowerManager.WakeLock =
    powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NitroBackgroundTimer").apply {
      setReferenceCounted(false)
    }

  private val core = BackgroundTimerCore(
    scheduler = object : TimerScheduler {
      override fun schedule(runnable: Runnable, delayMs: Long) {
        handler.postDelayed(runnable, delayMs)
      }

      override fun cancel(runnable: Runnable) {
        handler.removeCallbacks(runnable)
      }
    },
    wakeLock = object : TimerWakeLock {
      override val isHeld: Boolean
        get() = androidWakeLock.isHeld

      @SuppressLint("WakelockTimeout")
      override fun acquire() {
        androidWakeLock.acquire()
      }

      override fun release() {
        androidWakeLock.release()
      }
    },
    onError = { tag, message, throwable -> Log.e(tag, message, throwable) }
  )

  override fun setTimeout(id: Double, duration: Double, callback: (Double) -> Unit): Double =
    core.setTimeout(id, duration, callback)

  override fun clearTimeout(id: Double) {
    core.clearTimeout(id)
  }

  override fun setInterval(id: Double, interval: Double, callback: (Double) -> Unit): Double =
    core.setInterval(id, interval, callback)

  override fun clearInterval(id: Double) {
    core.clearInterval(id)
  }

  protected fun finalize() {
    core.cleanup()
  }
}
