package com.projectmika.purrtts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.projectmika.purrtts.PurrApplication
import com.projectmika.purrtts.R
import com.projectmika.purrtts.core.purr.VramResidencyPolicy
import com.projectmika.purrtts.engine.TtsBackendRouter
import com.projectmika.purrtts.ui.forge.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Optional persistent Foreground Service managed by TOOL-06 (`VRAM_&_KEEP_WARM_GOVERNOR`).
 * Shields resident neural TTS weights (e.g., Qwen3 1.7B VoiceDesign, Chatterbox, Kokoro FP32)
 * from Android's background `lmkd` while exposing one-tap Warm / Flush / Turn Off controls.
 */
class PurrKeepWarmService : Service() {

    companion object {
        private const val TAG = "PurrKeepWarmService"
        private const val CHANNEL_ID = "purr_keep_warm_channel"
        private const val NOTIFICATION_ID = 2504

        const val ACTION_START = "com.projectmika.purrtts.action.KEEP_WARM_START"
        const val ACTION_WARM_NOW = "com.projectmika.purrtts.action.KEEP_WARM_NOW"
        const val ACTION_FLUSH_RAM = "com.projectmika.purrtts.action.KEEP_WARM_FLUSH"
        const val ACTION_STOP_KEEP_WARM = "com.projectmika.purrtts.action.KEEP_WARM_STOP"

        fun syncWithState(context: Context, shouldRun: Boolean) {
            val intent = Intent(context, PurrKeepWarmService::class.java).apply {
                action = ACTION_START
            }
            if (shouldRun) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { Log.w(TAG, "Unable to start keep-warm foreground service: ${it.message}") }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private var lastNotificationSignature: String = ""
    private var lastNotifyAtMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? PurrApplication ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        val state = app.repository.state.value
        val shouldRun = VramResidencyPolicy.shouldRunForegroundService(
            config = state.vramResidency,
            moduleSlotted = VramResidencyPolicy.TOOL_ID in state.slottedCartridges
        )

        if (intent?.action == ACTION_STOP_KEEP_WARM) {
            app.repository.updateVramResidency { it.copy(foregroundKeepWarmService = false) }
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val initialSnap = app.backendRouter.residencyFlow.value
        val (targetLabel, _) = app.backendRouter.selectedEngineTargetLabel(app.repository.currentProfile, state)
        val startedOk = startForegroundCompat(buildNotification(initialSnap, targetLabel))
        if (!startedOk || !shouldRun) {
            if (!shouldRun) {
                stopForegroundCompat()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_WARM_NOW -> serviceScope.launch {
                app.backendRouter.warmSelectedProfile(app.repository.currentProfile)
            }
            ACTION_FLUSH_RAM -> serviceScope.launch {
                app.backendRouter.evictAllVram()
            }
        }

        startMonitoring(app)
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring(app: PurrApplication) {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            combine(app.backendRouter.residencyFlow, app.repository.state) { snap, state ->
                snap to state
            }.collect { (snap, state) ->
                val enabled = VramResidencyPolicy.shouldRunForegroundService(
                    config = state.vramResidency,
                    moduleSlotted = VramResidencyPolicy.TOOL_ID in state.slottedCartridges
                )
                if (!enabled) {
                    stopForegroundCompat()
                    stopSelf()
                    return@collect
                }
                val profile = state.profiles.firstOrNull { it.id == state.activeProfileId } ?: app.repository.currentProfile
                val (targetLabel, _) = app.backendRouter.selectedEngineTargetLabel(profile, state)
                val signature = "${snap.isWarming}|${snap.warmingLabel}|${snap.residentLabel}|${snap.remainingSeconds < 0}|${snap.effectiveTimeoutSec}|$targetLabel"
                val now = System.currentTimeMillis()
                // Only update countdown ticks at most every 15s unless structural residency state changed.
                val shouldNotify = signature != lastNotificationSignature || (now - lastNotifyAtMs >= 15_000L)
                if (shouldNotify) {
                    lastNotificationSignature = signature
                    lastNotifyAtMs = now
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(NOTIFICATION_ID, buildNotification(snap, targetLabel))
                }
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "PURR Keep-Warm Governor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps heavy neural TTS weights warm and protected in device RAM/VRAM."
                    setShowBadge(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    }.onFailure {
        Log.w(TAG, "startForeground failed: ${it.message}")
    }.getOrDefault(false)

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun buildNotification(snap: TtsBackendRouter.ResidencySnapshot, targetLabel: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val warmPending = PendingIntent.getService(
            this,
            11,
            Intent(this, PurrKeepWarmService::class.java).apply { action = ACTION_WARM_NOW },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val flushPending = PendingIntent.getService(
            this,
            12,
            Intent(this, PurrKeepWarmService::class.java).apply { action = ACTION_FLUSH_RAM },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this,
            13,
            Intent(this, PurrKeepWarmService::class.java).apply { action = ACTION_STOP_KEEP_WARM },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when {
            snap.isWarming -> "PURR Keep-Warm • Mounting ${snap.warmingLabel ?: targetLabel}…"
            snap.residentLabel != null -> "PURR Keep-Warm • HOT: ${snap.residentLabel}"
            else -> "PURR Keep-Warm • Standby ($targetLabel)"
        }
        val body = when {
            snap.isWarming -> "Loading weights into RAM/GPU so your first utterance speaks instantly"
            snap.residentLabel != null && snap.remainingSeconds < 0 ->
                "Pinned in RAM/VRAM (∞) • Protected from Android lmkd"
            snap.residentLabel != null && snap.remainingSeconds > 0 ->
                "Warm in RAM/VRAM (%d:%02d left) • Protected from lmkd".format(
                    snap.remainingSeconds / 60,
                    snap.remainingSeconds % 60
                )
            else -> "Cold standby • Tap WARM NOW to pre-mount $targetLabel into RAM"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_purr)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .addAction(0, "WARM NOW", warmPending)
            .addAction(0, "FLUSH RAM", flushPending)
            .addAction(0, "TURN OFF", stopPending)
            .build()
    }
}
