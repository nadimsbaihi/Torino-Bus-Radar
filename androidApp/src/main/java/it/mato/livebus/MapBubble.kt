package it.mato.livebus

import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.view.View
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.TriangleEdgeTreatment
import kotlin.math.roundToInt

/**
 * The bubbles over the map (a bus, a stop's arrivals, a pressed spot): a card in the app's own colours,
 * dark at night like the rest, with a small point at what was tapped. osmdroid's stock bubble is a
 * grey bitmap that ignores the theme.
 */
internal object MapBubble {
    private const val CORNER_DP = 16f
    private const val POINT_DP = 8f
    private const val PADDING_H_DP = 16f
    private const val PADDING_V_DP = 12f

    fun style(view: View) {
        val density = view.resources.displayMetrics.density
        val point = POINT_DP * density
        val card = MaterialShapeDrawable(ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, CORNER_DP * density)
            .setBottomEdge(TriangleEdgeTreatment(point, false))
            .build()).apply {
            fillColor = ColorStateList.valueOf(view.context.getColor(R.color.card))
            setStroke(density, view.context.getColor(R.color.line))
        }
        // The point hangs below the card, in room the inset keeps inside the view so it is not clipped.
        view.background = InsetDrawable(card, 0, 0, 0, point.roundToInt())
        val horizontal = (PADDING_H_DP * density).roundToInt()
        val vertical = (PADDING_V_DP * density).roundToInt()
        view.setPadding(horizontal, vertical, horizontal, vertical + point.roundToInt())
    }
}
