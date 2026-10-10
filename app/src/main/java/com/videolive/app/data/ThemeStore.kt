package com.videolive.app.data

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StyleRes
import com.videolive.app.R

/**
 * Premium UI theme + accent selection, persisted across restarts.
 * Theme changes are purely cosmetic — they never touch the stream engine.
 */
object ThemeStore {

    private const val PREF = "vl_theme"

    enum class Theme(val key: String, val label: String, @StyleRes val resId: Int) {
        SYSTEM("system", "System default", R.style.Theme_VideoLive_Graphite),
        GRAPHITE("graphite", "Dark Graphite", R.style.Theme_VideoLive_Graphite),
        BLACK("black", "Pure Black", R.style.Theme_VideoLive_Black),
        WHITE("white", "Clean White / Light", R.style.Theme_VideoLive_White),
        MIDNIGHT("midnight", "Midnight Blue", R.style.Theme_VideoLive_Midnight),
        EMERALD("emerald", "Emerald", R.style.Theme_VideoLive_Emerald),
        VIOLET("violet", "Violet", R.style.Theme_VideoLive_Violet),
        CYAN("cyan", "Cyan", R.style.Theme_VideoLive_Cyan);

        companion object {
            fun fromKey(key: String?): Theme =
                values().firstOrNull { it.key == key } ?: GRAPHITE
        }
    }

    enum class Accent(
        val key: String,
        val label: String,
        @StyleRes val overlayRes: Int
    ) {
        PURPLE("purple", "Purple", R.style.Accent_Purple),
        CYAN("cyan", "Cyan", R.style.Accent_Cyan),
        BLUE("blue", "Blue", R.style.Accent_Blue),
        TEAL("teal", "Teal", R.style.Accent_Teal),
        GREEN("green", "Green", R.style.Accent_Green),
        AMBER("amber", "Amber", R.style.Accent_Amber);

        companion object {
            fun fromKey(key: String?): Accent =
                values().firstOrNull { it.key == key } ?: PURPLE
        }
    }

    fun savedTheme(context: Context): Theme =
        Theme.fromKey(
            context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("theme", Theme.GRAPHITE.key)
        )

    /** Resolves SYSTEM to dark/light using the device configuration. */
    fun current(context: Context): Theme {
        val t = savedTheme(context)
        if (t != Theme.SYSTEM) return t
        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (dark) Theme.GRAPHITE else Theme.WHITE
    }

    fun accent(context: Context): Accent =
        Accent.fromKey(
            context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("accent", Accent.PURPLE.key)
        )

    fun saveTheme(context: Context, theme: Theme) {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("theme", theme.key).apply()
    }

    fun saveAccent(context: Context, accent: Accent) {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("accent", accent.key).apply()
    }
}
