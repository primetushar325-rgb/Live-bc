package com.videolive.app.data

import android.net.Uri
import com.videolive.app.media.LoadedVideo

/**
 * Process-wide holder for the currently loaded video. The streaming service and
 * the UI share it so the file is probed only once and never copied twice.
 */
object VideoRepository {

    @Volatile
    var current: LoadedVideo? = null

    /** The content Uri that [current] was prepared from (single-copy guard). */
    @Volatile
    var currentUri: Uri? = null
}
