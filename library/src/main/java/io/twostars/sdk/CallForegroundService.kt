package io.twostars.sdk

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Foreground service the SDK provides for hosting an active call.
 *
 * **Why you need it.** WebRTC media plus a Socket.IO control channel
 * are exactly the kind of background work Android's Doze + App
 * Standby aggressively suspend — typically within ~30 seconds of the
 * user switching to another app. That happens routinely during a
 * call (picking WhatsApp from a share sheet, answering an SMS, …)
 * and would silently kill the camera, drop the socket, and freeze
 * the call until the user came back. Running a foreground service
 * for the duration of the call keeps the process priority high
 * enough to avoid that.
 *
 * **Why it's a no-op.** The service holds no SDK state. It exists
 * purely to register a foreground notification of the right type
 * (CAMERA + MICROPHONE + MEDIA_PROJECTION). All call state still
 * lives on your [Room] handle.
 *
 * **Customizing the notification.** Pass a [Config] when starting:
 *
 * ```kotlin
 * CallForegroundService.start(
 *     context,
 *     CallForegroundService.Config(
 *         title = "Call with Jane Doe",
 *         text = "Tap to return",
 *         smallIconRes = R.drawable.ic_call,
 *         launchActivity = CallActivity::class.java,
 *     ),
 * )
 * // …
 * CallForegroundService.stop(context)
 * ```
 *
 * **Manifest.** The service tag and required permissions are
 * declared in this library's `AndroidManifest.xml`, so the manifest
 * merger picks them up — your app does **not** need to redeclare
 * them. POST_NOTIFICATIONS is a runtime permission on API 33+; if
 * the user denies it the service still runs but the notification is
 * silently suppressed.
 */
public class CallForegroundService : Service() {

    public data class Config(
        /** Notification title shown in the shade. */
        val title: String = "Call in progress",
        /** Notification body. */
        val text: String = "Tap to return to the call",
        /**
         * Status-bar icon. Defaults to a generic system call glyph;
         * pass your own drawable for branding.
         */
        @DrawableRes val smallIconRes: Int = android.R.drawable.ic_menu_call,
        /**
         * Notification channel id (API 26+). The default channel is
         * created lazily on first use; pass your own id if you want
         * to share a channel with other notifications in your app.
         */
        val channelId: String = DEFAULT_CHANNEL_ID,
        val channelName: String = "Active calls",
        val channelDescription: String = "Shown while a call is in progress",
        /** Stable notification id. */
        val notificationId: Int = DEFAULT_NOTIFICATION_ID,
        /**
         * Activity to launch when the user taps the notification. If
         * null we fall back to the host app's launcher activity (most
         * common case — the activity that started the call).
         */
        val launchActivity: Class<out Activity>? = null,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getIntExtra(EXTRA_MODE, MODE_NORMAL) ?: MODE_NORMAL
        val cfg = activeConfig ?: Config()
        ensureChannel(cfg)

        val launchIntent: Intent? = cfg.launchActivity
            ?.let { Intent(this, it) }
            ?: packageManager.getLaunchIntentForPackage(packageName)
        val tap: PendingIntent? = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, cfg.channelId)
            .setSmallIcon(cfg.smallIconRes)
            .setContentTitle(cfg.title)
            .setContentText(cfg.text)
            .setOngoing(true)
            .also { if (tap != null) it.setContentIntent(tap) }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE /* 34 */) {
            // Type bits depend on mode. MEDIA_PROJECTION cannot be
            // requested before the user has granted projection consent
            // — the OS throws SecurityException. So we boot in
            // CAMERA|MICROPHONE only and bump to add MEDIA_PROJECTION
            // via [enableMediaProjection] once Room.startScreenShare
            // fires (consent is fresh at that point).
            val typeBits = if (mode == MODE_WITH_PROJECTION) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(cfg.notificationId, notification, typeBits)
        } else {
            startForeground(cfg.notificationId, notification)
        }
        // Signal any caller awaiting the FGS-mode transition so the
        // suspending [enableMediaProjection] / [disableMediaProjection]
        // calls return only after startForeground has actually run.
        // Without this, MediaProjection.createVirtualDisplay races the
        // FGS bump and SecurityExceptions out.
        pendingModeTransition?.let {
            pendingModeTransition = null
            it.complete(Unit)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeConfig = null
        isStarted = false
        super.onDestroy()
    }

    private fun ensureChannel(cfg: Config) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(cfg.channelId) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                cfg.channelId,
                cfg.channelName,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = cfg.channelDescription
                setShowBadge(false)
            }
        )
    }

    public companion object {
        public const val DEFAULT_CHANNEL_ID: String = "twostars-active-call"
        public const val DEFAULT_NOTIFICATION_ID: Int = 0xF235

        private const val EXTRA_MODE = "io.twostars.sdk.CallForegroundService.MODE"
        private const val MODE_NORMAL = 0
        private const val MODE_WITH_PROJECTION = 1

        // Held process-static so onStartCommand can read it. The
        // service is intentionally START_NOT_STICKY (we don't want the
        // OS resurrecting a callless foreground service), so we don't
        // need Intent-extra round-tripping for restart durability.
        @Volatile
        private var activeConfig: Config? = null

        /**
         * Whether the service has been started by us this process.
         * Drives the SDK-internal [enableMediaProjection] / [disableMediaProjection]
         * calls — if a host app uses its own foreground service instead
         * of ours, those calls become no-ops and the host stays in
         * control of the type-bit dance.
         */
        @Volatile
        private var isStarted: Boolean = false

        // Awaitable for the next mode transition. enableMediaProjection /
        // disableMediaProjection install one before invoking startService;
        // onStartCommand completes it after startForeground returns. Lets
        // the suspending APIs guarantee the FGS state actually changed
        // before they return — without this, MediaProjection.createVirtualDisplay
        // races a not-yet-applied startForeground call and SecurityExceptions
        // out at the point of capture init.
        @Volatile
        private var pendingModeTransition: CompletableDeferred<Unit>? = null

        /** Start the call foreground service with the given [config]. */
        @JvmStatic
        @JvmOverloads
        public fun start(context: Context, config: Config = Config()) {
            activeConfig = config
            isStarted = true
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_MODE, MODE_NORMAL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Re-call `startForeground` with the MEDIA_PROJECTION type bit
         * added. Required on Android 14+ before
         * `MediaProjection.createVirtualDisplay` will succeed.
         *
         * Called automatically from [Room.startScreenShare] right after
         * projection consent is granted but before the capture pipeline
         * touches `MediaProjection`. No-op if [start] hasn't been
         * called — the host app is using its own foreground service and
         * is responsible for the type-bit bump itself.
         */
        @JvmStatic
        public suspend fun enableMediaProjection(context: Context) {
            if (!isStarted) return
            awaitModeTransition(context, MODE_WITH_PROJECTION)
        }

        /**
         * Drop the MEDIA_PROJECTION type bit, re-calling `startForeground`
         * with CAMERA|MICROPHONE only. Called automatically from
         * [Room.stopScreenShare]. No-op if [start] hasn't been called.
         *
         * Suspends until the FGS state actually changed (or a 3s timeout
         * fires, after which we move on best-effort).
         */
        @JvmStatic
        public suspend fun disableMediaProjection(context: Context) {
            if (!isStarted) return
            awaitModeTransition(context, MODE_NORMAL)
        }

        private suspend fun awaitModeTransition(context: Context, mode: Int) {
            val deferred = CompletableDeferred<Unit>()
            pendingModeTransition = deferred
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_MODE, mode)
            // Service is already running — startService just re-delivers
            // onStartCommand without re-elevating priority.
            context.startService(intent)
            try {
                // 3s buffer well inside the OS's 5s startForeground deadline
                // — onStartCommand normally lands within a single main-loop
                // tick, so this just bridges the dispatch latency.
                withTimeout(3_000) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                // Either the service didn't run (unlikely once started) or
                // startForeground threw inside onStartCommand. Drop the
                // pending deferred so a subsequent call can install a
                // fresh one, then continue best-effort — the next platform
                // call will surface the real error.
                if (pendingModeTransition === deferred) pendingModeTransition = null
            }
        }

        /** Stop the call foreground service. Safe to call when not running. */
        @JvmStatic
        public fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
            activeConfig = null
            isStarted = false
        }
    }
}
