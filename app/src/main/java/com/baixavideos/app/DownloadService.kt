package com.baixavideos.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Serviço em primeiro plano: mantém o download a correr com o app em segundo plano ou de ecrã desligado. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Downloads.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_PROGRESS,
            buildProgress(Downloads.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (downloadJob?.isActive == true) return START_NOT_STICKY

        acquireLocks()
        val job = Downloads.launchDownload(applicationContext)
        downloadJob = job

        // Atualiza a notificação no máximo 2x por segundo
        val updates = scope.launch {
            Downloads.state.sample(500).collect { s ->
                if (s.busy) notify(NOTIF_PROGRESS, buildProgress(s))
            }
        }
        job.invokeOnCompletion {
            scope.launch {
                updates.cancel()
                finish(Downloads.state.value)
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(s: UiState) {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val done = s.progress >= 1f
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (done) "Download concluído" else "Download não concluído")
            .setContentText(s.status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.status))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        notify(NOTIF_RESULT, notif)
        stopSelf()
    }

    private fun buildProgress(s: UiState) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(Downloads.state.value.preview?.title ?: "A baixar vídeo")
        .setContentText(s.status)
        .setProgress(100, (s.progress * 100).toInt().coerceIn(0, 100), s.progress < 0f)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openAppIntent())
        .addAction(0, "Cancelar", cancelIntent())
        .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
            // Sem permissão de notificações: o download continua na mesma
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BaixaVideos:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BaixaVideos:download")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_RESULT = 2
        private const val ACTION_CANCEL = "com.baixavideos.app.CANCEL"
    }
}
