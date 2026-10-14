package com.notebudget.app.feature.budget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** An open eye: "show the hidden amounts" (D272). */
internal val EyeIcon: ImageVector by lazy { eye("Eye", slashed = false) }

/** A crossed-out eye: "hide them again". */
internal val EyeOffIcon: ImageVector by lazy { eye("EyeOff", slashed = true) }

private fun eye(name: String, slashed: Boolean): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    // The outline of the eye and its pupil (Material "visibility").
    addPath(
        PathParser().parsePathString(
            "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
                "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
                "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    )
    if (slashed) {
        addPath(PathParser().parsePathString("M3.3,2L2,3.3L20.7,22L22,20.7z").toNodes(), fill = SolidColor(Color.Black))
    }
}.build()
