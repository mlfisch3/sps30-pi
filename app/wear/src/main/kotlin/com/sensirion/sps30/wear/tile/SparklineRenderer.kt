package com.sensirion.sps30.wear.tile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.sensirion.sps30.wear.AirQuality
import java.nio.ByteBuffer

/** A rendered sparkline: RGB_565 pixel bytes plus its dimensions, ready to hand to
 *  a ProtoLayout [androidx.wear.protolayout.ResourceBuilders.InlineImageResource]. */
class SparkImage(val bytes: ByteArray, val widthPx: Int, val heightPx: Int)

/**
 * Draws the last-hour PM2.5 series as a tiny filled sparkline. The line is colored
 * by the *current* (latest) air-quality level, with a translucent fill beneath it,
 * on the Tile's dark background. Output is RGB_565 so it drops straight into a
 * ProtoLayout inline image resource (2 bytes/pixel, no alpha channel needed).
 *
 * A pure renderer - no Android UI/framework state - so it is easy to reason about
 * and cheap to run inside the Tile resource request.
 */
object SparklineRenderer {

    private const val BG = 0xFF000000.toInt()

    /** RGB_565 bytes for a ProtoLayout inline image (the Tile). */
    fun render(values: FloatArray, widthPx: Int, heightPx: Int): SparkImage {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
        draw(Canvas(bitmap), values, widthPx, heightPx)
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        bitmap.recycle()
        return SparkImage(buffer.array(), widthPx, heightPx)
    }

    /** An ARGB_8888 [Bitmap] for a complication small image (Icon-backed). Same
     *  look as the Tile sparkline, on an opaque dark rounded field. */
    fun renderBitmap(values: FloatArray, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), values, widthPx, heightPx)
        return bitmap
    }

    private fun draw(canvas: Canvas, values: FloatArray, widthPx: Int, heightPx: Int) {
        canvas.drawColor(BG)

        if (values.size >= 2) {
            val color = AirQuality.of(values.last().toDouble()).color
            val pad = 2f
            val w = widthPx - 2 * pad
            val h = heightPx - 2 * pad

            // Vertical scale: pad the data range a little so a flat line sits mid-height
            // rather than pinned to an edge.
            var lo = values.min()
            var hi = values.max()
            if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
            val range = hi - lo

            fun x(i: Int) = pad + w * i / (values.size - 1)
            fun y(v: Float) = pad + h * (1f - (v - lo) / range)

            val line = Path().apply {
                moveTo(x(0), y(values[0]))
                for (i in 1 until values.size) lineTo(x(i), y(values[i]))
            }
            // Fill under the curve.
            val fill = Path(line).apply {
                lineTo(x(values.size - 1), pad + h)
                lineTo(x(0), pad + h)
                close()
            }
            canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                setColor(color)
                alpha = 60
            })
            canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                setColor(color)
            })
        }
    }
}
