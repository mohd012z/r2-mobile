package com.msa.r2mobile

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Where results you must find yourself are saved (encrypted files, patched copies, saved logs, projects):
 * Download/R2Mobile on shared storage, so the file manager and any other app can open them.
 * Falls back to the app's own folder only if shared storage is not writable.
 */
object PublicFolder {
    const val DISPLAY_PATH = "Download/R2Mobile"

    fun dir(context: Context): File {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val shared = File(Environment.getExternalStorageDirectory(), DISPLAY_PATH)
        if (allowed && (shared.isDirectory || shared.mkdirs()) && shared.canWrite()) return shared
        return context.getExternalFilesDir(null) ?: context.filesDir
    }
}
