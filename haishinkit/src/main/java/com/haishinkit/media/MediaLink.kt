package com.haishinkit.media

import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import com.haishinkit.BuildConfig
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.lang.Running
import com.haishinkit.metric.FrameTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * The MediaLink class can be used to synchronously play audio and video streams.
 */
class MediaLink(val audio: AudioCodec, val video: VideoCodec) :
    Running,
    CoroutineScope,
    Choreographer.FrameCallback {

    data class Buffer(
        val index: Int,
        val payload: ByteBuffer? = null,
        val timestamp: Long = 0L,
        val sync: Boolean = false
    )

    /**
     * Specifies the hasVideo indicates the video is present(TRUE), or not(FALSE).
     */
    var hasVideo = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (value) {
                video.startRunning()
            } else {
                video.stopRunning()
            }
        }

    /**
     * Specifies the hasAudio indicates the audio is present(TRUE), or not(FALSE).
     */
    var hasAudio = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (value) {
                audio.startRunning()
            } else {
                audio.stopRunning()
            }
        }

    /**
     * Specifies the paused indicates the playback of a media pause(TRUE) or not(FALSE).
     */
    var paused: Boolean
        get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED
        set(value) {
            when (audioTrack?.playState) {
                AudioTrack.PLAYSTATE_STOPPED -> {
                }
                AudioTrack.PLAYSTATE_PLAYING -> {
                    if (value) {
                        audioTrack?.pause()
                    }
                }
                AudioTrack.PLAYSTATE_PAUSED -> {
                    if (!value) {
                        audioTrack?.play()
                    }
                }
            }
        }

    override val isRunning = AtomicBoolean(false)
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    internal var audioTrack: AudioTrack? = null
        set(value) {
            syncMode = if (value == null) {
                field?.stop()
                field?.flush()
                field?.release()
                SYNC_MODE_CLOCK
            } else {
                SYNC_MODE_AUDIO
            }
            field = value
        }

    private var syncMode = SYNC_MODE_CLOCK
    private var hasKeyframe = false
    private val videoBuffers = LinkedBlockingDeque<Buffer>()
    private val audioBuffers = LinkedBlockingDeque<Buffer>()
    private var choreographer: Choreographer? = null
    private var videoTimestamp = Timestamp(1000L)
    private var handler: Handler? = null
        get() {
            if (field == null) {
                val thread =
                    HandlerThread(javaClass.name, android.os.Process.THREAD_PRIORITY_DISPLAY)
                thread.start()
                field = Handler(thread.looper)
            }
            return field
        }
        set(value) {
            field?.looper?.quitSafely()
            field = value
        }

    @Volatile
    private var keepAlive = true
    private var frameTracker: FrameTracker? = null
        get() {
            if (field == null && VERBOSE) {
                field = FrameTracker()
            }
            return field
        }
    private val audioDuration: Long
        get() {
            val track = audioTrack ?: return 0
            return (track.playbackHeadPosition.toLong() * 1000 / track.sampleRate) * 1000L + audioCorrection
        }
    private var audioCorrection = 0L
    private var audioPlaybackJob: Job? = null

    /**
     * Queues the audio data asynchronously for playback.
     */
    fun queueAudio(index: Int, payload: ByteBuffer?, timestamp: Long, sync: Boolean) {
        audioBuffers.add(Buffer(index, payload, timestamp, sync))
        if (!hasVideo) {
            val track = audioTrack ?: return
            if (track.playbackHeadPosition <= 0) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                return
            }
        }
    }

    /**
     * Queues the video data asynchronously for playback.
     */
    fun queueVideo(index: Int, payload: ByteBuffer?, timestamp: Long, sync: Boolean) {
        videoBuffers.add(Buffer(index, payload, timestamp, sync))
        if (choreographer == null) {
            handler?.post {
                choreographer = Choreographer.getInstance()
                choreographer?.postFrameCallback(this)
            }
        }
    }

    @Synchronized
    override fun startRunning() {
        if (isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "startRunning()")
        }
        audio.mode = Codec.Mode.DECODE
        keepAlive = true
        audioPlaybackJob = launch(coroutineContext) {
            doAudio()
        }
        isRunning.set(true)
    }

    @Synchronized
    override fun stopRunning() {
        if (!isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stopRunning()")
        }
        hasKeyframe = false
        handler = null
        choreographer?.removeFrameCallback(this)
        choreographer = null
        keepAlive = false
        audioPlaybackJob?.cancel()
        audioPlaybackJob = null
        syncMode = SYNC_MODE_CLOCK
        audioCorrection = 0
        videoTimestamp.clear()
        frameTracker?.clear()
        audio.release(audioBuffers)
        hasAudio = false
        video.release(videoBuffers)
        hasVideo = false
        isRunning.set(false)
    }

    override fun doFrame(frameTimeNanos: Long) {
        choreographer?.postFrameCallback(this)
        val duration: Long
        if (syncMode == SYNC_MODE_AUDIO) {
            val track = audioTrack ?: return
            if (track.playbackHeadPosition <= 0) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                    audioCorrection = videoTimestamp.duration
                }
                return
            }
            duration = audioDuration
        } else {
            videoTimestamp.nanoTime = frameTimeNanos
            duration = videoTimestamp.duration
        }
        try {
            val it = videoBuffers.iterator()
            var frameCount = 0
            while (it.hasNext()) {
                val buffer = it.next()
                if (!hasKeyframe) {
                    hasKeyframe = buffer.sync
                }
                if (buffer.timestamp <= duration) {
                    if (frameCount == 0 && hasKeyframe) {
                        if (VERBOSE) {
                            frameTracker?.track(FrameTracker.TYPE_VIDEO, SystemClock.uptimeMillis())
                        }
                        video.codec?.releaseOutputBuffer(buffer.index, buffer.timestamp * 1000)
                    } else {
                        video.codec?.releaseOutputBuffer(buffer.index, false)
                    }
                    frameCount++
                    it.remove()
                } else {
                    if (VERBOSE && 2 < frameCount) {
                        Log.d(TAG, "droppedFrame: ${frameCount - 1}")
                    }
                    break
                }
            }
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "", e)
            }
        }
    }

    private fun doAudio() {
        while (keepAlive) {
            try {
                val audio = audioBuffers.take()
                val payload = audio.payload ?: continue
                if (VERBOSE) {
                    frameTracker?.track(FrameTracker.TYPE_AUDIO, SystemClock.uptimeMillis())
                }
                while (payload.hasRemaining()) {
                    if (keepAlive) {
                        audioTrack?.write(
                            payload,
                            payload.remaining(),
                            AudioTrack.WRITE_NON_BLOCKING
                        )
                    } else {
                        break
                    }
                }
                this.audio.codec?.releaseOutputBuffer(audio.index, false)
            } catch (e: InterruptedException) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "", e)
                }
            } catch (e: IllegalStateException) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "", e)
                }
            }
        }
    }

    companion object {
        private const val SYNC_MODE_AUDIO = 0
        private const val SYNC_MODE_VSYNC = 1
        private const val SYNC_MODE_CLOCK = 2

        private const val VERBOSE = false
        private val TAG = MediaLink::class.java.simpleName
    }
}
