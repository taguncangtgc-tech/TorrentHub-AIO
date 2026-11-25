package com.torrenthub.aio.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.torrenthub.aio.MainActivity
import com.torrenthub.aio.R
import com.torrenthub.aio.data.SettingsRepository

object NotificationHelper {
    private const val CHANNEL_ID = "gemini_subtitle_channel"
    private const val DOWNLOAD_CHANNEL_ID = "torrenthub_downloads_channel"
    private const val NOTIF_ID_PROGRESS = 2001
    private const val NOTIF_ID_DONE = 2002

    const val EXTRA_OPEN_DOWNLOADS = "open_downloads"

    private fun localizedRes(context: Context) =
        LocaleHelper.getLocalizedResources(context, SettingsRepository.getInstance(context).appLanguage.code)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val res = localizedRes(context)
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                res.getString(R.string.header_gemini),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = res.getString(R.string.notif_channel_desc)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    fun ensureDownloadChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val res = localizedRes(context)
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                res.getString(R.string.dl_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = res.getString(R.string.dl_notif_channel_desc)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun canPost(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun contentPendingIntent(context: Context, openDownloads: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openDownloads) {
                putExtra(EXTRA_OPEN_DOWNLOADS, true)
            }
        }
        val reqCode = if (openDownloads) 101 else 0
        return PendingIntent.getActivity(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showTranslationProgress(context: Context, fileName: String, pct: Int, detail: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val res = localizedRes(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(res.getString(R.string.notif_translating_title, fileName, pct))
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceIn(0, 100), false)
            .setContentIntent(contentPendingIntent(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_PROGRESS, notif)
        } catch (_: SecurityException) {}
    }

    fun showTranslationFinished(context: Context, summary: String) {
        val mgr = NotificationManagerCompat.from(context)
        mgr.cancel(NOTIF_ID_PROGRESS)
        if (!canPost(context)) return
        ensureChannel(context)
        val res = localizedRes(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(res.getString(R.string.notif_done_title))
            .setContentText(summary)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent(context))
            .build()
        try {
            mgr.notify(NOTIF_ID_DONE, notif)
        } catch (_: SecurityException) {}
    }

    fun cancelProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_PROGRESS)
    }

    fun downloadNotifId(recordId: String): Int =
        3000 + (recordId.hashCode() and 0x0FFFFFFF) % 50000

    fun showDownloadProgress(
        context: Context,
        recordId: String,
        fileName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ) {
        if (!canPost(context)) return
        ensureDownloadChannel(context)
        val res = localizedRes(context)
        val indeterminate = totalBytes <= 0L
        val pct = if (!indeterminate) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
        val title = if (!indeterminate) {
            res.getString(R.string.dl_notif_progress_title, fileName, pct)
        } else {
            res.getString(R.string.dl_notif_progress_indeterminate, fileName)
        }
        val sizePart = if (!indeterminate) {
            "${Formatters.formatBytes(downloadedBytes)} / ${Formatters.formatBytes(totalBytes)}"
        } else {
            Formatters.formatBytes(downloadedBytes)
        }
        val speedPart = if (speedBytesPerSec > 0L) " · ${Formatters.formatBytes(speedBytesPerSec)}/s" else ""

        val notif = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(sizePart + speedPart)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(contentPendingIntent(context, openDownloads = true))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(downloadNotifId(recordId), notif)
        } catch (_: SecurityException) {}
    }

    fun showDownloadFinished(
        context: Context,
        recordId: String,
        fileName: String,
        success: Boolean,
        detail: String
    ) {
        val nId = downloadNotifId(recordId)
        val mgr = NotificationManagerCompat.from(context)
        mgr.cancel(nId)
        if (!canPost(context)) return
        ensureDownloadChannel(context)
        val res = localizedRes(context)
        val title = if (success) {
            res.getString(R.string.dl_notif_done_title)
        } else {
            res.getString(R.string.dl_notif_failed_title)
        }
        val icon = if (success) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }
        val notif = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("$title: $fileName")
            .setContentText(detail)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent(context, openDownloads = true))
            .build()
        try {
            mgr.notify(nId + 1, notif)
        } catch (_: SecurityException) {}
    }

    fun cancelDownloadNotification(context: Context, recordId: String) {
        val nId = downloadNotifId(recordId)
        val mgr = NotificationManagerCompat.from(context)
        mgr.cancel(nId)
        mgr.cancel(nId + 1)
    }
}
