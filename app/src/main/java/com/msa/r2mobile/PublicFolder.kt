package com.msa.r2mobile

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Where results you must find yourself are saved (encrypted files, patched copies, saved logs, projects):
 * Download/msar2 on shared storage, so the file manager and any other app can open them.
 * Falls back to the app's own folder only if shared storage cannot be created or written.
 */
object PublicFolder {
    const val DISPLAY_PATH = "Download/msar2"

    /** The shared msar2 folder, created if missing. Returns the app folder when that is not possible. */
    fun dir(context: Context): File {
        val shared = shared()
        if (shared.isDirectory || shared.mkdirs()) {
            if (shared.canWrite()) return shared
        }
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    fun shared(): File = File(Environment.getExternalStorageDirectory(), DISPLAY_PATH)

    /** True when the app has 'All files access' (always true before Android 11). */
    fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}
