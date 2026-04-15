/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A presentation queue that buffers color-converted frames (Bitmaps) and presents them at the
 * correct time based on their presentation timestamps.
 *
 * The queue maintains a configurable buffer delay (latency) to absorb timing variations and present
 * frames smoothly at their intended timestamps.
 *
 * Usage:
 * ```
 * val queue = PresentationQueue(
 *     bufferDelayMs = 100L, // 100ms latency buffer
 *     private val maxQueueSize: Int = 15,
 *     onFrameReady = { frame -> displayBitmap(frame.bitmap) }
 * )
 * queue.start()
 *
 * // After color conversion:
 * val bitmap = YuvToBitmapConverter.convert(videoFrame.buffer, width, height)
 * queue.enqueue(bitmap, videoFrame.presentationTimeUs)
 *
 * // When done:
 * queue.stop()
 * ```
 *
 * @param bufferDelayMs Target latency buffer in milliseconds. Higher values provide more jitter
 *   absorption but increase latency. Recommended range: 50-200ms.
 * @param maxQueueSize Maximum number of frames to buffer. Older frames are dropped if exceeded.
 * @param onFrameReady Callback invoked when a frame should be displayed.
 * @param clock Clock function for timing (injectable for testing).
 */
internal class PresentationQueue(
    private val bufferDelayMs: Long = DEFAULT_BUFFER_DELAY_MS,
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    private val onFrameReady: (PresentationFrame) -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

  private companion object {
    private const val TAG = "DAT:STREAM:PresentationQueue"
    private const val PRESENTATION_THREAD = "PresentationThread"

    // Default 100ms buffer delay - balances latency vs smoothness
    private const val DEFAULT_BUFFER_DELAY_MS = 100L

    // At 30fps, ~10 frames = 333ms of buffering capacity
    private const val DEFAULT_MAX_QUEUE_SIZE = 15

    // Minimum time between frame presentations (to prevent busy-spinning)
    private const val MIN_PRESENT_INTERVAL_MS = 5L

    // Maximum drift before resynchronizing (e.g., after pause/seek)
    private const val MAX_DRIFT_MS = 2000L

    // Threshold for considering a frame "late"
    private const val LATE_FRAME_THRESHOLD_MS = 16L // ~1 frame at 60fps
  }

  /** Represents a color-converted frame ready for presentation. */
  data class PresentationFrame(
      val bitmap: Bitmap,
      val presentationTimeUs: Long,
  ) : Comparable<PresentationFrame> {
    override fun compareTo(other: PresentationFrame): Int =
        presentationTimeUs.compareTo(other.presentationTimeUs)
  }

  // Frame queue sorted by presentation time
  private val frameQueue =
      PriorityBlockingQueue<PresentationFrame>(maxQueueSize + 1) // +1 to handle overflow check

  // Lock for synchronizing queue access between enqueue and tryPresentNextFrame
  private val queueLock = Any()

  // Presentation thread — guarded by `threadLock`
  private val threadLock = Any()
  private var presentationThread: HandlerThread? = null
  private var presentationHandler: Handler? = null

  // State
  private val running = AtomicBoolean(false)
  private val baseWallTimeMs = AtomicLong(-1L)
  private val basePresentationTimeUs = AtomicLong(-1L)

  // Presentation loop runnable
  private val presentationRunnable =
      object : Runnable {
        override fun run() {
          if (!running.get()) {
            return
          }

          val presented = tryPresentNextFrame()
          val delay = if (presented) MIN_PRESENT_INTERVAL_MS else 1L

          synchronized(threadLock) { presentationHandler }?.postDelayed(this, delay)
        }
      }

  /** Start the presentation queue. */
  fun start() {
    if (running.getAndSet(true)) {
      Log.d(TAG, "Already running")
      return
    }

    Log.d(TAG, "Starting with bufferDelayMs=$bufferDelayMs, maxQueueSize=$maxQueueSize")

    // Reset timing
    baseWallTimeMs.set(-1L)
    basePresentationTimeUs.set(-1L)

    synchronized(threadLock) {
      presentationThread =
          HandlerThread(PRESENTATION_THREAD, Process.THREAD_PRIORITY_DISPLAY).apply { start() }

      presentationHandler = presentationThread?.looper?.let { looper -> Handler(looper) }

      // Start presentation loop
      presentationHandler?.post(presentationRunnable)
    }
  }

  /** Stop the presentation queue and release resources. */
  fun stop() {
    if (!running.getAndSet(false)) {
      Log.d(TAG, "Already stopped")
      return
    }

    Log.d(TAG, "Stopping")

    synchronized(threadLock) {
      presentationHandler?.removeCallbacksAndMessages(null)
      presentationThread?.quit()
      presentationThread = null
      presentationHandler = null
    }

    // Clear remaining frames and recycle their bitmaps
    synchronized(queueLock) {
      while (true) {
        val frame = frameQueue.poll() ?: break
        frame.bitmap.recycle()
      }
    }
  }

  /**
   * Enqueue a color-converted frame for presentation.
   *
   * @param bitmap The color-converted bitmap ready for display
   * @param presentationTimeUs Presentation timestamp in microseconds (from VideoFrame)
   */
  fun enqueue(bitmap: Bitmap, presentationTimeUs: Long) {
    if (!running.get()) {
      Log.d(TAG, "Not running, dropping frame")
      return
    }

    // Clone the bitmap since the caller may reuse/recycle the original
    val clonedBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)

    val frame =
        PresentationFrame(
            bitmap = clonedBitmap,
            presentationTimeUs = presentationTimeUs,
        )

    val dropped: PresentationFrame?
    synchronized(queueLock) {
      // Check queue capacity and drop oldest if full
      dropped = if (frameQueue.size >= maxQueueSize) frameQueue.poll() else null
      frameQueue.offer(frame)
    }

    // Recycle outside lock
    if (dropped != null) {
      dropped.bitmap.recycle()
      Log.d(TAG, "Queue full, dropped frame ts=${dropped.presentationTimeUs}")
    }
  }

  private fun tryPresentNextFrame(): Boolean {
    val frame: PresentationFrame
    val now = clock()

    synchronized(queueLock) {
      frame = frameQueue.peek() ?: return false

      // Initialize base times on first frame - add bufferDelayMs to wallTime
      // This ensures we wait at least bufferDelayMs before presenting first frame
      if (baseWallTimeMs.get() < 0) {
        baseWallTimeMs.set(now + bufferDelayMs)
        basePresentationTimeUs.set(frame.presentationTimeUs)
        Log.d(
            TAG,
            "Initialized timing: wallTime=$now + delay=$bufferDelayMs, pts=${frame.presentationTimeUs}",
        )
      }

      // Calculate when this frame should be presented
      // Frame should be presented when: elapsed wall time >= elapsed presentation time
      val elapsedSinceBase = now - baseWallTimeMs.get()
      val targetElapsedUs = frame.presentationTimeUs - basePresentationTimeUs.get()
      val targetElapsedMs = targetElapsedUs / 1000

      // Check for large drift (resync needed)
      val drift = elapsedSinceBase - targetElapsedMs
      if (kotlin.math.abs(drift) > MAX_DRIFT_MS) {
        Log.d(TAG, "Large drift detected (${drift}ms), resyncing")
        baseWallTimeMs.set(now + bufferDelayMs)
        basePresentationTimeUs.set(frame.presentationTimeUs)
        // Don't present yet after resync - wait for buffer to build
        return false
      }

      // Is it time to present this frame?
      // elapsedSinceBase will be negative until bufferDelayMs has passed
      if (elapsedSinceBase < targetElapsedMs) {
        return false
      }

      // Remove from queue
      frameQueue.poll()

      // Check if frame is too late (should have been presented earlier)
      val lateMs = elapsedSinceBase - targetElapsedMs
      if (lateMs > LATE_FRAME_THRESHOLD_MS) {
        Log.d(TAG, "Frame late by ${lateMs}ms")
      }
    }

    // Present the frame outside the lock to avoid blocking producers
    try {
      onFrameReady(frame)
    } catch (e: Exception) {
      Log.e(TAG, "Error presenting frame", e)
    }

    return true
  }
}
