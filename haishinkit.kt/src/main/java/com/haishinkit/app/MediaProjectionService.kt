package com.haishinkit.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.haishinkit.event.Event
import com.haishinkit.event.EventUtils
import com.haishinkit.event.IEventListener
import com.haishinkit.graphics.effect.LanczosVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.AudioRecordSource
import com.haishinkit.media.MediaProjectionSource
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpStream

class MediaProjectionService : Service(), IEventListener {
    private lateinit var stream: RtmpStream
    private lateinit var connection: RtmpConnection
    private lateinit var videoSource: MediaProjectionSource

    private var messenger: Messenger? = null
    private var handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_CONNECT -> connection.connect(Preference.shared.rtmpURL)
                MSG_CLOSE -> connection.close()
                MSG_SET_VIDEO_EFFECT -> {
                    if (msg.obj is LanczosVideoEffect) {
                        val lanczosVideoEffect = msg.obj as LanczosVideoEffect
                        lanczosVideoEffect.texelWidth = videoSource.resolution.width.toFloat()
                        lanczosVideoEffect.texelHeight = videoSource.resolution.height.toFloat()
                        stream.videoEffect = lanczosVideoEffect
                        return
                    }
                    stream.videoEffect = msg.obj as VideoEffect
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return messenger?.binder
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel =
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            channel.description = CHANNEL_DESC
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setColorized(true)
            setSmallIcon(R.mipmap.ic_launcher)
            setStyle(NotificationCompat.DecoratedCustomViewStyle())
            setContentTitle(NOTIFY_TITLE)
        }.build()
        if (Build.VERSION_CODES.Q <= Build.VERSION.SDK_INT) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notification)
        }
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        stream.attachAudio(AudioRecordSource(this))
        stream.listener = listener
        data?.let {
            val source = MediaProjectionSource(
                this,
                mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it),
                metrics
            )
            stream.attachVideo(source)
            stream.videoSetting.width = source.resolution.width shr 2
            stream.videoSetting.height = source.resolution.height shr 2
            videoSource = source
            Log.e(TAG, "${stream.videoSetting.width}:${stream.videoSetting.height}")
        }
        connection.connect(Preference.shared.rtmpURL)
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(handler)
        connection = RtmpConnection()
        connection.addEventListener(Event.RTMP_STATUS, this)
        stream = RtmpStream(connection)
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.dispose()
    }

    override fun handleEvent(event: Event) {
        Log.i(TAG, event.toString())
        val data = EventUtils.toMap(event)
        val code = data["code"].toString()
        if (code == RtmpConnection.Code.CONNECT_SUCCESS.rawValue) {
            stream.publish(Preference.shared.streamName)
        }
    }

    companion object {
        const val ID = 1
        const val CHANNEL_ID = "MediaProjectionID"
        const val CHANNEL_NAME = "MediaProjectionService"
        const val CHANNEL_DESC = ""
        const val NOTIFY_TITLE = "Recording."

        var metrics = DisplayMetrics()
        var data: Intent? = null
        var listener: RtmpStream.Listener? = null

        const val MSG_CONNECT = 0
        const val MSG_CLOSE = 1
        const val MSG_SET_VIDEO_EFFECT = 2

        private val TAG = MediaProjectionSource::class.java.simpleName
    }
}
