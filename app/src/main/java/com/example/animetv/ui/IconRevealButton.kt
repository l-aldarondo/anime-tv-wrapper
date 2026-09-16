package com.example.animetv.ui

import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.ViewGroup
import android.widget.Button

/**
 * Netflix/Prime-style action button: shows only [icon] at rest and expands to "[icon]  [label]"
 * once the button gains D-pad focus, animating the width change on the parent row. [icon] and
 * [label] can each be updated independently at any time (e.g. to swap the favorite icon between
 * "+"/"✓", or to append an episode code to a label) without re-registering focus handling.
 */
class IconRevealButton(private val button: Button, icon: String, label: String) {
    var icon: String = icon
        set(value) { field = value; render() }
    var label: String = label
        set(value) { field = value; render() }

    init {
        button.setOnFocusChangeListener { _, _ -> render() }
        render()
    }

    private fun render() {
        (button.parent as? ViewGroup)?.let {
            TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(150))
        }
        button.text = if (button.isFocused) "$icon  $label" else icon
    }
}
