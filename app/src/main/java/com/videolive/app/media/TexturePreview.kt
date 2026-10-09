package com.videolive.app.media

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.TextureView
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.model.FrameMode

/**
 * Phase-2 preview: a TextureView whose transform matrix is computed from the
 * SAME framing settings the encoder uses (Fit/Fill + zoom + pan), so what the
 * user sees is exactly what the stream sends.
 *
 * The preview canvas itself is aspect-correct (the container is resized to
 * 9:16 or 16:9 by the activity), therefore:
 *  - FIT letterboxes identically to the encoder's pad filter,
 *  - FILL crops identically to the encoder's crop filter,
 *  - zoom/pan translate/scale identically to the encoder's scale+crop math.
 *
 * Playback is local MediaPlayer only; it never touches the streaming engine.
 */
class TexturePreview(private val view: TextureView) : TextureView.SurfaceTextureListener {

    data class Framing(
        val mode: FrameMode,
        val zoomPct: Int,
        val panXPct: Int,
        val panYPct: Int
    )

    var framing = Framing(FrameMode.FIT, 100, 0, 0)

    var onEnded: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    private var player: MediaPlayer? = null
    private var prepared = false
    private var videoW = 0
    private var videoH = 0

    init {
        view.surfaceTextureListener = this
    }

    fun play(context: Context, uri: Uri) {
        stop()
        prepared = false
        videoW = 0
        videoH = 0
        try {
            val p = MediaPlayer()
            p.setDataSource(context.applicationContext, uri)
            p.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
            p.setOnPreparedListener {
                prepared = true
                videoW = it.videoWidth
                videoH = it.videoHeight
                maybeStart()
            }
            p.setOnVideoSizeChangedListener { mp, w, h ->
                videoW = w
                videoH = h
                refreshMatrix()
            }
            p.setOnCompletionListener { onEnded?.invoke() }
            p.setOnErrorListener { _, what, extra ->
                LogStore.event("Preview playback error $what/$extra")
                onFailed?.invoke()
                true
            }
            p.prepareAsync()
            player = p
        } catch (t: Throwable) {
            LogStore.event("Preview start failed: ${t.javaClass.simpleName}")
            onFailed?.invoke()
        }
    }

    fun stop() {
        val p = player
        player = null
        prepared = false
        if (p != null) {
            try {
                p.setOnPreparedListener(null)
                p.setOnCompletionListener(null)
                p.setOnErrorListener(null)
                p.stop()
            } catch (_: Throwable) {
            }
            try {
                p.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun refreshMatrix() {
        applyMatrix()
    }

    private fun maybeStart() {
        val p = player ?: return
        val st = view.surfaceTexture ?: return
        try {
            p.setSurface(android.view.Surface(st))
            p.start()
            applyMatrix()
        } catch (_: Throwable) {
        }
    }

    /** Same math as FFmpegCommandBuilder.videoFilter, mirrored for the view. */
    private fun applyMatrix() {
        val vw = videoW.toFloat()
        val vh = videoH.toFloat()
        val cw = view.width.toFloat()
        val ch = view.height.toFloat()
        if (vw <= 0f || vh <= 0f || cw <= 0f || ch <= 0f) return

        val fit = minOf(cw / vw, ch / vh)
        val fill = maxOf(cw / vw, ch / vh)
        val base = if (framing.mode == FrameMode.FILL) fill else fit
        val z = framing.zoomPct.coerceIn(100, 300) / 100f
        val sx = base * z
        val sy = base * z

        val scaledW = vw * sx
        val scaledH = vh * sy
        var dx = (cw - scaledW) / 2f
        var dy = (ch - scaledH) / 2f

        // Pan moves within the hidden (cropped) area, like the encoder crop.
        val extraX = maxOf(scaledW - cw, 0f)
        val extraY = maxOf(scaledH - ch, 0f)
        dx -= framing.panXPct.coerceIn(-100, 100) / 100f * extraX / 2f
        dy -= framing.panYPct.coerceIn(-100, 100) / 100f * extraY / 2f

        val m = Matrix()
        m.setScale(sx, sy)
        m.postTranslate(dx, dy)
        view.setTransform(m)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (prepared) maybeStart()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyMatrix()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
