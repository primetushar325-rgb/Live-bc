package com.videolive.app.util

import android.app.Activity
import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.videolive.app.data.ThemeStore

/**
 * Applies the persisted theme BEFORE setContentView so every resource
 * (layouts, drawables, dialogs) resolves the themeable attrs consistently.
 */
object ThemeEngine {

    fun apply(activity: Activity) {
        activity.setTheme(ThemeStore.current(activity).resId)
        // Accent is a forced overlay so any theme + any accent combine.
        activity.theme.applyStyle(ThemeStore.accent(activity).overlayRes, true)
    }

    /** Resolves a themeable color attr for code-side coloring. */
    @ColorInt
    fun color(context: Context, @AttrRes attr: Int): Int {
        val ta = context.theme.obtainStyledAttributes(intArrayOf(attr))
        return try {
            ta.getColor(0, 0)
        } finally {
            ta.recycle()
        }
    }
}
