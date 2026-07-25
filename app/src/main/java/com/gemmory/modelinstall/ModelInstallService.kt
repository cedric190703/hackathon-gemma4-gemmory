package com.gemmory.modelinstall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.gemmory.R
import com.gemmory.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and visible while a multi-gigabyte install runs.
 *
 * The service does not own the transfer; [ModelInstaller] does. It only mirrors
 * installer state into a notification and stops itself when the install ends,
 * which keeps download logic testable on the JVM.
 */
class ModelInstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.model_install_notification_title), null))

        val installer = application.container.modelInstaller
        scope.launch {
            installer.state.collectLatest { state ->
                when (state) {
                    is ModelInstallState.Downloading -> notify(
                        text = "${state.downloadedBytes.toMegabytes()} MB of " +
                            "${state.totalBytes.toMegabytes()} MB",
                        progress = (state.fraction * 100).toInt(),
                    )

                    is ModelInstallState.Importing -> notify(text = "Copying file…", progress = null)

                    is ModelInstallState.Verifying -> notify(
                        text = "Verifying checksum…",
                        progress = (state.fraction * 100).toInt(),
                    )

                    else -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(text: String, progress: Int?) {
        getSystemService<NotificationManager>()
            ?.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun buildNotification(text: String, progress: Int?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.model_install_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress != null) setProgress(100, progress, false) else setProgress(0, 0, true)
            }
            .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.model_install_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "model_install"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ModelInstallService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ModelInstallService::class.java))
        }
    }
}

private fun Long.toMegabytes(): Long = this / (1024 * 1024)
