package com.example.kidsafe.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.kidsafe.core.Col
import com.example.kidsafe.core.EventType
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.util.Date

/**
 * Child-initiated screen share. Nothing in the parent app can start or extend this — the child
 * must tap the button and accept Android's own permission prompt every single time. While it runs:
 *  - Android shows its own capture indicator that cannot be hidden, plus our notification with
 *    a "Stop sharing" action
 *  - a low-resolution still image is sent every couple of seconds (not smooth video, no audio)
 *  - it always stops by itself after the time the child picked (24 hours max)
 */
class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var captureRunnable: Runnable? = null
    private var endAtMillis = 0L
    private var started = false

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_STOP) stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW),
        )
        val filter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started || intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val minutes = intent.getIntExtra(EXTRA_MINUTES, DEFAULT_MINUTES).let { if (it == ALL_TIME) ALL_TIME else it.coerceIn(1, MAX_MINUTES) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true

        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(minutes),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        proj.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            },
            handler,
        )
        projection = proj

        val metrics = resources.displayMetrics
        val frameW = FRAME_WIDTH
        val frameH = (FRAME_WIDTH.toFloat() * metrics.heightPixels / metrics.widthPixels)
            .toInt().coerceAtLeast(1)

        val reader = ImageReader.newInstance(frameW, frameH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = try {
            proj.createVirtualDisplay(
                "KidSafeShare", frameW, frameH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler,
            )
        } catch (e: Exception) {
            null
        }
        if (virtualDisplay == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        endAtMillis = if (minutes == ALL_TIME) Long.MAX_VALUE else System.currentTimeMillis() + minutes * 60_000L
        publishStart(minutes)
        scheduleCapture()
        if (minutes != ALL_TIME) handler.postDelayed({ stopSelf() }, minutes * 60_000L)
        return START_NOT_STICKY
    }

    private fun scheduleCapture() {
        captureRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                captureFrame()
                if (endAtMillis == Long.MAX_VALUE || System.currentTimeMillis() < endAtMillis) {
                    handler.postDelayed(this, FRAME_INTERVAL_MS)
                }
            }
        }
        captureRunnable = r
        handler.postDelayed(r, 700L) // let the virtual display warm up first
    }

    private fun captureFrame() {
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888,
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            bmp.recycle()

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            cropped.recycle()
            upload(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
        } catch (e: Exception) {
            // Skip this one frame; the next tick will try again.
        } finally {
            image.close()
        }
    }

    private fun upload(frameBase64: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val data = hashMapOf<String, Any>(
            "active" to true,
            "frame" to frameBase64,
            "ts" to FieldValue.serverTimestamp(),
        )
        if (endAtMillis != Long.MAX_VALUE) {
            data["endsAt"] = Timestamp(Date(endAtMillis))
        }
        FirebaseFirestore.getInstance()
            .collection(Col.CHILDREN).document(uid)
            .collection(Col.SCREENSHARE).document("live")
            .set(data)
    }

    private fun publishStart(minutes: Int) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid)
        val event = mapOf(
            "type" to EventType.SCREEN_SHARE_START,
            "minutes" to minutes,
            "ts" to FieldValue.serverTimestamp(),
        )
        ref.update("lastEvent", event)
        ref.collection(Col.EVENTS).add(event)
    }

    private fun publishEnd() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid)
        ref.collection(Col.SCREENSHARE).document("live")
            .set(mapOf("active" to false, "frame" to "", "ts" to FieldValue.serverTimestamp()))
        val event = mapOf("type" to EventType.SCREEN_SHARE_END, "ts" to FieldValue.serverTimestamp())
        ref.update("lastEvent", event)
        ref.collection(Col.EVENTS).add(event)
    }

    override fun onDestroy() {
        captureRunnable?.let { handler.removeCallbacks(it) }
        runCatching { unregisterReceiver(stopReceiver) }
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        if (started) publishEnd()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(minutes: Int): Notification {
        val stopIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_STOP).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sharing your screen with your parent")
            .setContentText(if (minutes == ALL_TIME) "Sharing continues until you tap Stop." else "Ends by itself in $minutes min, or tap Stop below.")
            .setOngoing(true)
            .addAction(0, "Stop sharing", stopIntent)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MINUTES = "minutes"
        const val MAX_MINUTES = 1440 // 24 hours
        const val ALL_TIME = -1 // Until the child manually stops sharing
        const val DEFAULT_MINUTES = 1440 // 24 hours
        private const val ACTION_STOP = "com.example.kidsafe.child.STOP_SHARE"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIF_ID = 3001
        private const val FRAME_WIDTH = 480
        private const val FRAME_INTERVAL_MS = 2_000L
        private const val JPEG_QUALITY = 40
    }
}
