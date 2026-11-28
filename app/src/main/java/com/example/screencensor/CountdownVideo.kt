package com.example.screencensor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File

/** The video that plays when the countdown ends: either the one built into the app or one the user picked. */
object CountdownVideo {
    const val REQ = 7711

    fun file(ctx: Context) = File(ctx.filesDir, "countdown_custom.mp4")

    fun status(ctx: Context): String =
        if (file(ctx).exists()) "Video: your own (" + (file(ctx).length() / 1048576).coerceAtLeast(1) + " MB)"
        else "Video: the default one built into the app"

    /** Opens the phone's file picker. The result comes back to the activity's onActivityResult -> [handle]. */
    fun pick(a: Activity) {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }
            @Suppress("DEPRECATION")
            a.startActivityForResult(Intent.createChooser(i, "Choose the countdown video"), REQ)
        } catch (e: Exception) {
            Toast.makeText(a, "No file picker found", Toast.LENGTH_LONG).show()
        }
    }

    fun reset(ctx: Context) {
        file(ctx).delete()
        Toast.makeText(ctx, "Using the default video again", Toast.LENGTH_SHORT).show()
    }

    /** Call from onActivityResult. Returns true if the result was ours. Keeps a private copy of the chosen video. */
    fun handle(a: Activity, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ) return false
        val uri = if (res == Activity.RESULT_OK) data?.data else null
        if (uri == null) return true
        Toast.makeText(a, "Copying the video...", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = false
            val dest = file(a)
            try {
                a.contentResolver.openInputStream(uri)?.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                ok = dest.length() > 0
            } catch (e: Exception) { }
            if (!ok) dest.delete()
            a.runOnUiThread {
                Toast.makeText(a, if (ok) "Countdown video changed" else "Couldn't read that video", Toast.LENGTH_LONG).show()
            }
        }.start()
        return true
    }
}
