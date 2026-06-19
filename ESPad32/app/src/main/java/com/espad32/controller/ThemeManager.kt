package com.espad32.controller

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton

enum class PanelTheme(val label: String) {
    DARK_GLASS("Dark Glass"),
    MINIMAL_FLAT("Minimal Flat"),
    TACTICAL_HUD("Tactical HUD")
}

object ThemeManager {

    private const val PREFS_KEY = "panelTheme"
    var current = PanelTheme.DARK_GLASS

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("ESPad32Prefs", Context.MODE_PRIVATE)
        current = try {
            PanelTheme.valueOf(prefs.getString(PREFS_KEY, PanelTheme.DARK_GLASS.name) ?: PanelTheme.DARK_GLASS.name)
        } catch (e: Exception) { PanelTheme.DARK_GLASS }
    }

    fun save(context: Context, theme: PanelTheme) {
        current = theme
        context.getSharedPreferences("ESPad32Prefs", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, theme.name).apply()
    }

    fun apply(panel: ViewGroup) {
        // Panel background
        panel.setBackgroundColor(when (current) {
            PanelTheme.DARK_GLASS    -> 0xEE0D1E26.toInt()
            PanelTheme.MINIMAL_FLAT  -> 0xFF1A1A1A.toInt()
            PanelTheme.TACTICAL_HUD  -> 0xFF000000.toInt()
        })

        // Apply to all buttons recursively
        applyToChildren(panel)
    }

    private fun applyToChildren(view: View) {
        when (view) {
            is ToggleButton -> styleButton(view)
            is Button       -> styleButton(view)
            is ViewGroup    -> for (i in 0 until view.childCount) applyToChildren(view.getChildAt(i))
        }
    }

    private fun styleButton(btn: Button) {
        val drawableRes = when (current) {
            PanelTheme.DARK_GLASS   -> R.drawable.btn_glass
            PanelTheme.MINIMAL_FLAT -> R.drawable.btn_minimal
            PanelTheme.TACTICAL_HUD -> R.drawable.btn_hud
        }
        btn.setBackgroundResource(drawableRes)
        btn.setTextColor(0xFFFFFFFF.toInt())
        btn.textSize = when (current) {
            PanelTheme.TACTICAL_HUD -> 10f
            else -> 11f
        }
        btn.typeface = when (current) {
            PanelTheme.TACTICAL_HUD -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        btn.isAllCaps = current == PanelTheme.TACTICAL_HUD
        btn.minHeight = 0
        btn.minimumHeight = 0
        // Pill height
        val heightDp = 48
        val px = (heightDp * btn.context.resources.displayMetrics.density).toInt()
        btn.layoutParams?.height = px
    }
}
