package io.twostars.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Finger-paint canvas that mirrors the JS SDK's pen shape.
 *
 * On stroke end ([MotionEvent.ACTION_UP] / `_CANCEL`) it fires
 * [onStrokeFinished] with the completed stroke serialised to the
 * same wire shape the JS WhiteboardEditor emits:
 *
 * ```json
 * { "type":"pen", "points":[[x,y],[x,y],…], "color":"#000000", "strokeWidth":3 }
 * ```
 *
 * Coordinates are raw pixels from the touch event — matches what the
 * JS editor sends. If you need cross-platform pixel parity, normalise
 * here and de-normalise at render time.
 *
 * Render incoming strokes from peers via [addRemoteStroke]; clear
 * everything via [clearAll].
 */
class WhiteboardCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var strokeColor: Int = Color.parseColor("#1f2937")
    var strokePxWidth: Float = 6f

    /** Called once per finished stroke. Pass to `room.annotateWhiteboard`. */
    var onStrokeFinished: ((stroke: JsonObject) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }

    /**
     * One stroke = (color, strokeWidth, list of points). Points are
     * stored as raw pixel coordinates so we can render without an
     * extra scaling pass; the wire shape is identical.
     */
    private data class Stroke(
        val color: Int,
        val width: Float,
        val points: MutableList<FloatArray> = mutableListOf(),
        val path: Path = Path(),
    )

    private val finishedStrokes = mutableListOf<Stroke>()
    private var inProgress: Stroke? = null

    // No init { setBackgroundColor(...) }: the canvas needs to be
    // transparent so the AI-generated SVG rendered in the WebView
    // sibling shows through. The parent FrameLayout already paints a
    // white tile background, so strokes still have contrast.

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Stop the parent ScrollView from stealing the gesture as
                // soon as the finger moves vertically — without this every
                // downward stroke gets eaten by the scroll handler.
                parent?.requestDisallowInterceptTouchEvent(true)
                val s = Stroke(color = strokeColor, width = strokePxWidth)
                s.path.moveTo(event.x, event.y)
                s.points.add(floatArrayOf(event.x, event.y))
                inProgress = s
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = inProgress ?: return true
                // Capture every historical sample inside this frame so
                // fast finger drags don't get aliased into straight lines.
                for (i in 0 until event.historySize) {
                    s.path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                    s.points.add(floatArrayOf(event.getHistoricalX(i), event.getHistoricalY(i)))
                }
                s.path.lineTo(event.x, event.y)
                s.points.add(floatArrayOf(event.x, event.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val s = inProgress ?: return true
                inProgress = null
                if (s.points.size >= 2) {
                    finishedStrokes.add(s)
                    onStrokeFinished?.invoke(serialise(s))
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (s in finishedStrokes) drawStroke(canvas, s)
        inProgress?.let { drawStroke(canvas, it) }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) {
        paint.color = s.color
        paint.strokeWidth = s.width
        canvas.drawPath(s.path, paint)
    }

    /**
     * Render one peer's stroke onto this canvas. Accepts a JsonObject
     * shaped like the one [serialise] produces (the wire shape from the
     * server's `whiteboard-annotations` event).
     */
    fun addRemoteStroke(shape: JsonObject) {
        if (shape["type"]?.jsonPrimitive?.contentOrNull != "pen") return
        val pts = shape["points"] as? JsonArray ?: return
        val color = shape["color"]?.jsonPrimitive?.contentOrNull?.let {
            runCatching { Color.parseColor(it) }.getOrDefault(Color.RED)
        } ?: Color.RED
        val width = shape["strokeWidth"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 6f
        val s = Stroke(color = color, width = width)
        var first = true
        for (p in pts) {
            val pair = (p as? JsonArray) ?: continue
            if (pair.size < 2) continue
            val x = pair[0].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: continue
            val y = pair[1].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: continue
            if (first) { s.path.moveTo(x, y); first = false } else s.path.lineTo(x, y)
            s.points.add(floatArrayOf(x, y))
        }
        if (s.points.size >= 2) {
            finishedStrokes.add(s)
            invalidate()
        }
    }

    /** Wipe all strokes (own + remote). */
    fun clearAll() {
        finishedStrokes.clear()
        inProgress = null
        invalidate()
    }

    private fun serialise(s: Stroke): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("pen"))
        put("color", JsonPrimitive(String.format("#%06X", 0xFFFFFF and s.color)))
        put("strokeWidth", JsonPrimitive(s.width))
        put("points", buildJsonArray {
            for (pt in s.points) {
                add(buildJsonArray {
                    add(JsonPrimitive(pt[0]))
                    add(JsonPrimitive(pt[1]))
                })
            }
        })
    }
}
