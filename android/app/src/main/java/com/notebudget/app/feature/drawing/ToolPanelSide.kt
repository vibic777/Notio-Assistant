package com.notebudget.app.feature.drawing

/**
 * Where the tool panel of a drawing / blueprint lies over the sheet (D261, D281): at the bottom (the default), on the right, at
 * the top or on the left. Chosen from the panel's own menu and kept in the settings.
 */
enum class ToolPanelSide {
    BOTTOM, RIGHT, TOP, LEFT;

    /** A side panel is a column; a top or bottom one a band across the whole width. */
    val vertical: Boolean get() = this == RIGHT || this == LEFT

    companion object {
        /** The stored name; before D281 only "on the right" was stored, as a yes / no. */
        fun of(name: String?, oldRight: Boolean = false): ToolPanelSide =
            entries.firstOrNull { it.name == name } ?: if (oldRight) RIGHT else BOTTOM
    }
}
