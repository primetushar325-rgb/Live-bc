package com.videolive.app.data

import android.content.Context
import androidx.annotation.StyleRes
import com.videolive.app.R

/**
 * Premium UI theme selection, persisted across restarts.
 */
object ThemeStore {

    private const val PREF = "vl_theme"

    enum class Theme(val key: String, val label: String, @StyleRes val resId: Int) {
        GRAPHITE("graphite", "Graphite", R.style.Theme_VideoLive_Graphite),
        BLACK("black", "Pure Black", R.style.Theme_VideoLive_Black),
        WHITE("white", "Clean White", R.style.Theme_VideoLive_White),
        MIDNIGHT("midnight", "Midnight Blue", R.style.Theme_VideoLive_Midnight),
        EMERALD("emerald", "Emerald", R.style.Theme_VideoLive_Emerald),
        VIOLET("violet", "Violet", R.style.Theme_VideoLive_Violet),
        CYAN("cyan", "Cyan", R.style.Theme_VideoLive_Cyan);

        companion object {
            fun fromKey(key: String?): Theme =
                values().firstOrNull { it.key == key } ?: GRAPHITE
        }
    }

    fun current(context: Context): Theme =
        Theme.fromKey(
            context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("theme", Theme.GRAPHITE.key)
        )

    fun save(context: Context, theme: Theme) {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("theme", theme.key).apply()
    }
}
