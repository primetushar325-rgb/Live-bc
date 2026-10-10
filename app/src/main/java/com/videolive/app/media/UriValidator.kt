package com.videolive.app.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.videolive.app.ffmpeg.LogStore

/**
 * Result of validating a content:// Uri returned by ACTION_OPEN_DOCUMENT.
 * The Uri is always treated as an opaque content Uri — NEVER as a file path.
 */
data class UriCheck(
    val openable: Boolean,
    val permissionDenied: Boolean,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long
)

object UriValidator {

    fun check(context: Context, uri: Uri): UriCheck {
        var permissionDenied = false
        var openable = false
        var sizeBytes = -1L

        val mimeType = try {
            context.contentResolver.getType(uri)
        } catch (t: Throwable) {
            null
        }

        val displayName = queryDisplayName(context, uri)

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                openable = true
                sizeBytes = pfd.statSize
            }
        } catch (e: SecurityException) {
            permissionDenied = true
            LogStore.event("URI validation: permission denied for $uri")
        } catch (t: Throwable) {
            openable = false
            LogStore.event("URI validation: cannot open ($uri): ${t.javaClass.simpleName}")
        }

        return UriCheck(openable, permissionDenied, mimeType, displayName, sizeBytes)
    }

    fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (t: Throwable) {
        null
    }
}
