package com.torrenthub.aio.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.torrenthub.aio.R
import com.torrenthub.aio.ui.AppViewModel

object ExternalPlayerHelper {

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * Membuka magnet link menggunakan Intent.ACTION_VIEW + Intent.createChooser agar pengguna
     * bebas memilih aplikasi pemutar streaming magnet / klien torrent eksternal.
     * Menampilkan Toast informatif jika belum ada aplikasi pendukung magnet yang terpasang.
     */
    fun streamMagnetWithChooser(context: Context, magnet: String, vm: AppViewModel) {
        val cleanMagnet = magnet.trim()
        if (cleanMagnet.isEmpty()) return

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanMagnet))
        try {
            val handlers = context.packageManager.queryIntentActivities(viewIntent, 0)
            if (handlers.isEmpty()) {
                throw ActivityNotFoundException("No installed app supports magnet scheme")
            }
            val chooser = Intent.createChooser(viewIntent, vm.str(R.string.chooser_stream_magnet))
            val activity = context.findActivity()
            if (activity != null) {
                activity.startActivity(chooser)
            } else {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        } catch (_: ActivityNotFoundException) {
            copyToClipboard(context, "Magnet Link", cleanMagnet)
            val msg = vm.str(R.string.toast_no_magnet_player)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            vm.emitToast(msg)
        } catch (_: Exception) {
            copyToClipboard(context, "Magnet Link", cleanMagnet)
            val msg = vm.str(R.string.toast_no_magnet_player)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            vm.emitToast(msg)
        }
    }

    // Membuka URL streaming video menggunakan Intent.createChooser dengan tipe MIME video
    // sehingga pengguna bebas memilih pemutar video favorit (VLC, MX Player, MPV, dll.).
    fun openVideoWithChooser(
        context: Context,
        streamUrl: String,
        videoTitle: String,
        vm: AppViewModel
    ) {
        val cleanUrl = streamUrl.trim()
        if (cleanUrl.isEmpty()) return

        val playIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(cleanUrl), "video/*")
            putExtra(Intent.EXTRA_TITLE, videoTitle)
            putExtra("title", videoTitle)
            putExtra("name", videoTitle)
        }
        try {
            val handlers = context.packageManager.queryIntentActivities(playIntent, 0)
            if (handlers.isEmpty()) {
                throw ActivityNotFoundException("No installed external video player found")
            }
            val chooser = Intent.createChooser(playIntent, vm.str(R.string.chooser_open_with_video))
            val activity = context.findActivity()
            if (activity != null) {
                activity.startActivity(chooser)
            } else {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        } catch (_: ActivityNotFoundException) {
            copyToClipboard(context, "PikPak Stream URL", cleanUrl)
            val msg = vm.str(R.string.toast_pikpak_stream_copied_no_player)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            vm.emitToast(msg)
        } catch (_: Exception) {
            copyToClipboard(context, "PikPak Stream URL", cleanUrl)
            val msg = vm.str(R.string.toast_pikpak_stream_copied_no_player)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            vm.emitToast(msg)
        }
    }
}
