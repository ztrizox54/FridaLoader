package com.dns.fridaloader

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import java.util.Locale

object Util {
    fun getRootDirPath(context: Context): String {
        return if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()) {
            ContextCompat.getExternalFilesDirs(context, null)[0].absolutePath
        } else {
            context.filesDir.absolutePath
        }
    }

    fun getProgressDisplayLine(currentBytes: Long, totalBytes: Long): String {
        return "${toMB(currentBytes)} / ${toMB(totalBytes)}"
    }

    private fun toMB(bytes: Long) =
        String.format(Locale.ENGLISH, "%.2f MB", bytes / (1024.0 * 1024.0))
}
