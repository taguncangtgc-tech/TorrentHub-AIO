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
    private const val NOTIF_ID_PROGRESS = 2001
    private const val NOTIF_ID_DONE = 2002

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

    private fun canPost(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun contentPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
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
}
