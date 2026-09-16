package com.example.animetv.ui

import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.ViewGroup
import android.widget.Button
import androidx.annotation.DrawableRes

/**
 * Same focus-reveal-label behavior as [IconRevealButton], but backed by a simple vector-drawable
 * icon instead of a text glyph. Some Unicode symbols (eye, house, magnifying glass, clapperboard)
 * render as busy, full-color emoji on Android instead of a plain line icon — this uses a flat
 * single-color vector drawable so the icon stays minimal, matching Netflix/Prime/Apple TV style.
 */
class IconDrawableRevealButton(private val button: Button, @DrawableRes iconRes: Int, label: String) {
    var label: String = label
        set(value) { field = value; render() }

    init {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        button.setOnFocusChangeListener { _, _ -> render() }
        render()
    }

    private fun render() {
        (button.parent as? ViewGroup)?.let {
            TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(150))
        }
        val focused = button.isFocused
        val density = button.context.resources.displayMetrics.density
        button.compoundDrawablePadding = if (focused) (8 * density).toInt() else 0
        button.text = if (focused) label else ""
    }
}
